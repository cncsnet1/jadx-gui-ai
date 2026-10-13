package jadx.gui.ai.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.analysis.ApkProfile.Confidence;
import jadx.gui.ai.analysis.ApkProfile.Kind;
import jadx.gui.ai.analysis.ApkProfile.NativeLibrary;

/**
 * No extensions, external commands, archive extraction, decompilation or network access are
 * required.
 */
public final class ApkProfileScanner {
	private static final int MAX_ENTRIES = 100_000;
	private static final int MAX_LIBRARIES = 128;
	private static final int ELF_READ_LIMIT = 4 * 1024 * 1024;
	private static final long TOTAL_READ_LIMIT = 24L * 1024 * 1024;
	private static final long MAX_ARCHIVE_SIZE = 2L * 1024 * 1024 * 1024;
	private static final long TIME_LIMIT = 12_000_000_000L;

	private final ApkProfile result = new ApkProfile();
	private final Map<String, List<AiEvidence>> signals = new LinkedHashMap<>();
	private final Map<String, Integer> inputNames = new LinkedHashMap<>();
	private final AiCancellation token;
	private final long started = System.nanoTime();
	private long remainingBytes = TOTAL_READ_LIMIT;
	private int entries;

	private ApkProfileScanner(AiCancellation token) {
		this.token = token;
	}

	/** Can also be used before loading classes to obtain a resource-only preflight. */
	public static ApkProfile scan(List<Path> inputs, AiCancellation token) throws InterruptedException {
		return scan(inputs, List.of(), token);
	}

	public static ApkProfile scan(List<Path> inputs, List<String> classNames, AiCancellation token) throws InterruptedException {
		token.check();
		ApkProfileScanner scanner = new ApkProfileScanner(token);
		for (Path input : inputs) {
			if (!scanner.withinBudget()) {
				break;
			}
			scanner.archive(input);
		}
		int classCount = 0;
		for (String name : classNames) {
			if (++classCount > 200_000 || !scanner.withinBudget()) {
				break;
			}
			scanner.className(name);
		}
		token.check();
		scanner.finish();
		return scanner.result;
	}

	private boolean withinBudget() throws InterruptedException {
		token.check();
		if (System.nanoTime() - started > TIME_LIMIT) {
			if (result.getGaps().stream().noneMatch(gap -> gap.startsWith("time_limit"))) {
				result.gap("time_limit", "");
			}
			return false;
		}
		return true;
	}

	private void archive(Path path) throws InterruptedException {
		String fileName = display(path.getFileName() == null ? "input" : path.getFileName().toString());
		int duplicate = inputNames.merge(fileName, 1, Integer::sum);
		String location = duplicate == 1 ? fileName : fileName + " [" + duplicate + ']';
		if (!Files.isRegularFile(path)) {
			result.gap("input_unavailable", location);
			return;
		}
		try {
			if (Files.size(path) > MAX_ARCHIVE_SIZE) {
				result.gap("archive_size_limit", location);
				return;
			}
			byte[] magic;
			try (InputStream input = Files.newInputStream(path)) {
				magic = input.readNBytes(4);
			}
			if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K') {
				// Standalone DEX/CLASS are valid JADX inputs, but they have no APK container to profile.
				if (magic.length == 4 && magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x') {
					result.dex();
					signal("dex", location, "DEX input (header only; business-code coverage is not established)");
				} else if (path.getFileName().toString().toLowerCase(Locale.ROOT).contains(".apk")) {
					result.gap("archive_unreadable", location);
				}
				return;
			}
			String zipLimit = zipDirectoryLimit(path);
			if (zipLimit != null) {
				result.gap(zipLimit, location);
				return;
			}
			try (ZipFile zip = new ZipFile(path.toFile())) {
				result.archive();
				Enumeration<? extends ZipEntry> iterator = zip.entries();
				while (iterator.hasMoreElements() && withinBudget()) {
					if (++entries > MAX_ENTRIES) {
						result.gap("entry_limit", location);
						break;
					}
					ZipEntry entry = iterator.nextElement();
					if (!entry.isDirectory()) {
						entry(zip, entry, location);
					}
				}
			}
		} catch (IOException | IllegalArgumentException e) {
			result.gap("archive_unreadable", location);
		}
	}

	/** Bound the central directory before ZipFile allocates its in-memory index. */
	private String zipDirectoryLimit(Path path) throws IOException, InterruptedException {
		try (SeekableByteChannel channel = Files.newByteChannel(path)) {
			long size = channel.size();
			int tailSize = (int) Math.min(size, 65_557);
			ByteBuffer tail = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN);
			channel.position(size - tailSize);
			while (tail.hasRemaining() && withinBudget()) {
				if (channel.read(tail) < 0) {
					break;
				}
			}
			if (tail.hasRemaining()) {
				return "archive_unreadable";
			}
			for (int offset = tailSize - 22; offset >= 0; offset--) {
				if (tail.getInt(offset) != 0x06054b50 || offset + 22 + Short.toUnsignedInt(tail.getShort(offset + 20)) != tailSize) {
					continue;
				}
				int count = Short.toUnsignedInt(tail.getShort(offset + 10));
				long directorySize = Integer.toUnsignedLong(tail.getInt(offset + 12));
				long directoryOffset = Integer.toUnsignedLong(tail.getInt(offset + 16));
				if (count == 65_535 || directorySize == 0xffff_ffffL || directoryOffset == 0xffff_ffffL
						|| directorySize > 32L * 1024 * 1024) {
					// ZIP64 metadata needs a separate bounded reader; do not hand it to an unbounded ZIP index.
					return "archive_size_limit";
				}
				if (count + entries > MAX_ENTRIES) {
					return "entry_limit";
				}
				if (directoryOffset > size || directorySize > size - directoryOffset) {
					return "archive_unreadable";
				}
				return null;
			}
			return "archive_unreadable";
		}
	}

	private void entry(ZipFile zip, ZipEntry entry, String archive) throws InterruptedException {
		String name = entry.getName().replace('\\', '/');
		String lower = name.toLowerCase(Locale.ROOT);
		String source = archive + "!/" + display(name);
		String base = lower.substring(lower.lastIndexOf('/') + 1);
		if (lower.endsWith(".dex")) {
			result.dex();
			signal("dex", source, "DEX file present; presence does not establish readable business-code coverage");
		}
		if (lower.endsWith(".js") || lower.endsWith(".bundle") || lower.endsWith(".hbc")) {
			result.javaScript();
			signal("javascript", source, "JavaScript or bundle asset present; may be minified or compiled bytecode");
		}
		if (base.equals("libflutter.so")) {
			signal("flutter_runtime", source, "Flutter runtime library");
		}
		if (base.equals("libapp.so")) {
			signal("flutter_app", source, "Library named libapp.so; framework corroboration required");
		}
		if (lower.startsWith("assets/flutter_assets/")) {
			signal("flutter_assets", source, "Flutter assets directory");
		}
		if (lower.matches("assets/apps/[^/]+/www/.*")) {
			signal("dcloud_www", source, "DCloud packaged web application assets");
			if (base.equals("app-service.js") || base.equals("app-config-service.js") || base.equals("uni-app-view.umd.js")) {
				signal("uniapp_bundle", source, "uni-app application bundle layout");
			}
		}
		if (lower.startsWith("assets/data/") && (base.equals("dcloud_control.xml") || base.equals("dcloud_properties.xml"))) {
			signal("dcloud_runtime", source, "DCloud runtime configuration");
		}
		if (base.equals("libreactnativejni.so") || base.equals("libreactnative.so")) {
			signal("react_runtime", source, "React Native runtime library");
		}
		if (base.equals("index.android.bundle")) {
			signal("react_bundle", source, "Default React Native bundle name; runtime corroboration required");
		}
		if ((lower.startsWith("assets/") || lower.startsWith("res/raw/")) && (lower.endsWith(".bundle") || lower.endsWith(".hbc"))) {
			if (lower.endsWith(".bundle")) {
				signal("react_bundle", source, "Packaged bundle candidate; React Native runtime corroboration required");
			}
			try {
				if (remainingBytes >= 12) {
					byte[] header = read(zip, entry, 12);
					remainingBytes -= header.length;
					if (ScriptContent.isHermes(header)) {
						signal("hermes_bytecode", source, ScriptContent.hermesDescription(header));
					}
				}
			} catch (IOException e) {
				result.gap("bundle_header_unreadable", source);
			}
		}
		if (base.equals("libhermes.so")) {
			signal("hermes", source, "Hermes engine; bundle content may be bytecode");
		}
		if ((lower.startsWith("assets/") || lower.startsWith("res/raw/")) && (lower.endsWith(".html") || lower.endsWith(".htm"))) {
			signal("web_assets", source, "Packaged HTML asset; does not establish app-wide WebView use");
		}
		if (lower.equals("assets/www/cordova.js") || lower.equals("assets/www/cordova_plugins.js")) {
			signal("cordova_bundle", source, "Cordova packaged runtime/plugin registry; page/config corroboration required");
		}
		if (lower.equals("res/xml/config.xml") || lower.equals("assets/www/config.xml")) {
			signal("hybrid_config", source, "Hybrid configuration candidate; bridge and packaged-page corroboration required");
		}
		if (base.equals("libunity.so")) {
			signal("unity_runtime", source, "Unity runtime library; application data corroboration required");
		}
		if (lower.startsWith("assets/bin/data/") && (base.equals("globalgamemanagers") || base.equals("data.unity3d"))) {
			signal("unity_data", source, "Unity packaged application data; content has not been decoded");
		}
		if (lower.startsWith("assets/bin/data/managed/") && base.endsWith(".dll")) {
			signal("unity_managed", source, "Managed assembly inventory only; CLI metadata and C# source have not been recovered");
		}
		boolean il2cppMetadata = lower.endsWith("/il2cpp_data/metadata/global-metadata.dat")
				|| lower.equals("assets/bin/data/managed/metadata/global-metadata.dat");
		if (base.equals("libil2cpp.so") || il2cppMetadata) {
			signal("unity_il2cpp", source, "IL2CPP artifact inventory only; native functions and metadata mapping have not been recovered");
		}
		if (il2cppMetadata) {
			signal("unity_metadata", source, "IL2CPP application metadata artifact; tables and native mappings have not been decoded");
		}
		String protector = protectionLibrary(base);
		if (protector != null) {
			signal("protection:" + protector, source, "Known protection-associated file name; verify loader and runtime behavior");
		}
		if (lower.endsWith(".so")) {
			result.nativeLibrary();
			signal("native", source, "Native library entry");
			if (result.getNativeLibraries().size() < MAX_LIBRARIES) {
				String[] parts = name.split("/");
				String abi = parts.length > 2 && parts[0].equals("lib") ? parts[1] : "unknown";
				NativeLibrary library = new NativeLibrary(source, display(abi));
				try {
					int limit = (int) Math.min(ELF_READ_LIMIT, remainingBytes);
					if (limit < 64) {
						library.limit("read_limit");
					} else {
						byte[] bytes = read(zip, entry, limit);
						remainingBytes -= bytes.length;
						if (entry.getSize() < 0 || entry.getSize() > bytes.length) {
							library.limit("read_limit");
						}
						elf(bytes, library);
					}
				} catch (IOException | IllegalArgumentException e) {
					library.limit("elf_header_invalid");
				}
				library.evidence(evidence(source, "ELF=" + library.isElf() + ", ABI=" + abi + ", architecture="
						+ library.getArchitecture() + ", bits=" + library.getBitness() + "\nJNI exports: " + library.getJniSymbols()
						+ "\nSymbol index only; dynamic RegisterNatives targets and machine-code behavior are not resolved.",
						!library.getLimitations().isEmpty()));
				result.addNative(library);
			} else if (result.getNativeLibraryCount() == MAX_LIBRARIES + 1) {
				result.gap("native_limit", archive);
			}
		}
	}

	private byte[] read(ZipFile zip, ZipEntry entry, int limit) throws IOException, InterruptedException {
		try (InputStream input = zip.getInputStream(entry); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			while (output.size() < limit && withinBudget()) {
				int read = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
				if (read < 0) {
					break;
				}
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private static String protectionLibrary(String name) {
		if (name.equals("libjiagu.so") || name.equals("libjiagu_art.so") || name.equals("libjiagu_a64.so")) {
			return "360 Jiagu";
		}
		if (name.equals("libsecmain.so") || name.equals("libsecexe.so")) {
			return "Bangcle";
		}
		if (name.equals("libshella.so") || name.equals("libshellx.so")) {
			return "Tencent Legu";
		}
		if (name.equals("libexec.so") || name.equals("libexecmain.so")) {
			// These names are not distinctive enough alone; no brand verdict from a generic library name.
			return null;
		}
		return null;
	}

	private void className(String name) {
		if (name.startsWith("io.flutter.embedding.") || name.equals("io.flutter.view.FlutterView")) {
			classSignal("flutter_class", name, "Flutter embedding class");
		}
		if (name.startsWith("io.dcloud.")) {
			classSignal("dcloud_runtime", name, "DCloud SDK class; it can also be embedded in a native application");
		}
		if (name.equals("com.facebook.react.ReactApplication") || name.equals("com.facebook.react.ReactActivity")) {
			classSignal("react_class", name, "React Native application/entry class");
		}
		if (name.startsWith("org.apache.cordova.") || name.startsWith("com.getcapacitor.")) {
			classSignal("web_runtime", name, "Hybrid WebView bridge framework class");
		}
		if (name.startsWith("org.apache.cordova.")) {
			classSignal("cordova_class", name, "Cordova bridge class; packaged page corroboration required");
		}
		if (name.equals("com.unity3d.player.UnityPlayer") || name.equals("com.unity3d.player.UnityPlayerActivity")) {
			classSignal("unity_class", name, "Unity player embedding; application data corroboration required");
		}
		String protector = null;
		if (name.equals("com.stub.StubApp")) {
			protector = "360 Jiagu";
		} else if (name.equals("com.secneo.apkwrapper.ApplicationWrapper")) {
			protector = "SecNeo";
		} else if (name.equals("com.tencent.StubShell.TxAppEntry")) {
			protector = "Tencent Legu";
		}
		if (protector != null) {
			classSignal("protection:" + protector, name, "Known protection-associated loader class name; not a confirmed unpacking result");
		}
	}

	private void classSignal(String key, String name, String description) {
		List<AiEvidence> evidence = signals.computeIfAbsent(key, ignored -> new ArrayList<>());
		if (evidence.size() < 6) {
			evidence.add(new AiEvidence(JadxNodeRef.forCls(name), display(name), description,
					AiEvidence.fingerprint(name + '\n' + description), false));
		}
	}

	private void signal(String key, String source, String description) {
		List<AiEvidence> evidence = signals.computeIfAbsent(key, ignored -> new ArrayList<>());
		if (evidence.size() < 6) {
			evidence.add(evidence(source, description, false));
		}
	}

	private static AiEvidence evidence(String source, String description, boolean partial) {
		return new AiEvidence(null, display(source), description, AiEvidence.fingerprint(source + '\n' + description), partial);
	}

	private List<AiEvidence> sources(String... keys) {
		List<AiEvidence> evidence = new ArrayList<>();
		for (String key : keys) {
			evidence.addAll(signals.getOrDefault(key, List.of()));
		}
		return evidence;
	}

	private boolean has(String key) {
		return signals.containsKey(key);
	}

	private void finish() {
		for (String key : signals.keySet()) {
			if (key.startsWith("protection:")) {
				result.fact(Kind.PROTECTION, "protection_marker", key.substring("protection:".length()),
						Confidence.SUSPECTED, sources(key));
				result.recommend("check_protection");
			}
		}
		if (result.getFacts().isEmpty()) {
			result.fact(Kind.PROTECTION, "protection_unknown", "", Confidence.UNKNOWN, List.of());
		}
		boolean technology = false;
		if (has("flutter_runtime") && has("flutter_assets")) {
			technology("technology_flutter", "Flutter", "flutter_runtime", "flutter_assets", "flutter_app", "flutter_class");
			result.recommend("inspect_native");
			technology = true;
		}
		if (has("uniapp_bundle") && has("dcloud_runtime")) {
			technology("technology_uniapp", "uni-app", "uniapp_bundle", "dcloud_runtime", "dcloud_www");
			result.recommend("inspect_javascript");
			technology = true;
		} else if (has("dcloud_runtime") || has("dcloud_www")) {
			technology("technology_dcloud", "DCloud", "dcloud_runtime", "dcloud_www");
			technology = true;
		}
		if ((has("react_runtime") || has("react_class")) && (has("react_bundle") || has("hermes_bytecode"))) {
			technology("technology_react_native", "React Native", "react_runtime", "react_class", "react_bundle", "hermes_bytecode");
			result.recommend("inspect_javascript");
			technology = true;
		}
		if (has("web_assets") && has("web_runtime")) {
			technology("technology_webview", "WebView", "web_assets", "web_runtime");
			result.recommend("inspect_javascript");
			technology = true;
		}
		if (has("web_assets") && (has("cordova_class") || has("cordova_bundle")) && (has("hybrid_config") || has("cordova_bundle"))) {
			technology("technology_cordova", "Cordova", "web_assets", "cordova_class", "cordova_bundle", "hybrid_config");
			technology = true;
		}
		if ((has("unity_runtime") || has("unity_class")) && (has("unity_data") || has("unity_managed") || has("unity_metadata"))) {
			technology("technology_unity", "Unity", "unity_runtime", "unity_class", "unity_data", "unity_managed", "unity_il2cpp");
			technology = true;
		}
		if (has("dex")) {
			technology("technology_android", "DEX", "dex");
			technology = true;
		}
		if (!technology) {
			result.fact(Kind.TECHNOLOGY, "technology_unknown", "", Confidence.UNKNOWN, List.of());
		}
		visibility("dex", "visibility_dex", result.getDexCount());
		visibility("javascript", "visibility_javascript", result.getJavaScriptCount());
		visibility("native", "visibility_native", result.getNativeLibraryCount());
		if (!has("dex") && !has("javascript") && !has("native")) {
			result.fact(Kind.VISIBILITY, "visibility_unknown", "", Confidence.UNKNOWN, List.of());
		}
		result.recommend("inspect_requests");
		if (result.getNativeLibraryCount() > 0) {
			result.recommend("inspect_native");
		}
		result.complete();
	}

	private void technology(String code, String value, String... signals) {
		result.fact(Kind.TECHNOLOGY, code, value, Confidence.OBSERVED, sources(signals));
	}

	private void visibility(String key, String code, int count) {
		if (count > 0) {
			result.fact(Kind.VISIBILITY, code, Integer.toString(count), Confidence.OBSERVED, sources(key));
		}
	}

	private void elf(byte[] bytes, NativeLibrary library) throws InterruptedException {
		if (bytes.length < 52 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F'
				|| (bytes[4] != 1 && bytes[4] != 2) || (bytes[5] != 1 && bytes[5] != 2)) {
			library.limit("elf_header_invalid");
			return;
		}
		boolean elf64 = bytes[4] == 2;
		if (elf64 && bytes.length < 64) {
			library.limit("elf_header_invalid");
			return;
		}
		ByteBuffer data = ByteBuffer.wrap(bytes).order(bytes[5] == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		int machine = Short.toUnsignedInt(data.getShort(18));
		String architecture;
		switch (machine) {
			case 3:
				architecture = "x86";
				break;
			case 8:
				architecture = "MIPS";
				break;
			case 40:
				architecture = "ARM";
				break;
			case 62:
				architecture = "x86_64";
				break;
			case 183:
				architecture = "AArch64";
				break;
			case 243:
				architecture = "RISC-V";
				break;
			default:
				architecture = "machine=" + machine;
		}
		library.header(elf64 ? 64 : 32, architecture);
		long sectionOffset = elf64 ? data.getLong(40) : Integer.toUnsignedLong(data.getInt(32));
		int sectionSize = Short.toUnsignedInt(data.getShort(elf64 ? 58 : 46));
		int sectionCount = Short.toUnsignedInt(data.getShort(elf64 ? 60 : 48));
		if (sectionCount == 0 || sectionCount > 4096 || sectionSize < (elf64 ? 64 : 40)
				|| !range(sectionOffset, (long) sectionCount * sectionSize, bytes.length)) {
			library.limit("elf_symbols_unavailable");
			return;
		}
		boolean found = false;
		for (int index = 0; index < sectionCount && withinBudget(); index++) {
			int section = (int) sectionOffset + index * sectionSize;
			int type = data.getInt(section + 4);
			if (type != 2 && type != 11) {
				continue;
			}
			long offset = elf64 ? data.getLong(section + 24) : Integer.toUnsignedLong(data.getInt(section + 16));
			long size = elf64 ? data.getLong(section + 32) : Integer.toUnsignedLong(data.getInt(section + 20));
			long stride = elf64 ? data.getLong(section + 56) : Integer.toUnsignedLong(data.getInt(section + 36));
			int link = data.getInt(section + (elf64 ? 40 : 24));
			if (link < 0 || link >= sectionCount || stride < (elf64 ? 24 : 16) || !range(offset, size, bytes.length)) {
				library.limit("elf_symbols_unavailable");
				continue;
			}
			int strings = (int) sectionOffset + link * sectionSize;
			long stringOffset = elf64 ? data.getLong(strings + 24) : Integer.toUnsignedLong(data.getInt(strings + 16));
			long stringSize = elf64 ? data.getLong(strings + 32) : Integer.toUnsignedLong(data.getInt(strings + 20));
			if (data.getInt(strings + 4) != 3 || !range(stringOffset, stringSize, bytes.length)) {
				library.limit("elf_symbols_unavailable");
				continue;
			}
			found = true;
			long count = size / stride;
			if (count > 100_000) {
				library.limit("elf_symbol_limit");
			}
			for (int symbol = 0; symbol < Math.min(count, 100_000) && withinBudget(); symbol++) {
				int position = (int) (offset + symbol * stride);
				int info = Byte.toUnsignedInt(bytes[position + (elf64 ? 4 : 12)]);
				int visibility = bytes[position + (elf64 ? 5 : 13)] & 3;
				int binding = info >>> 4;
				int sectionIndex = Short.toUnsignedInt(data.getShort(position + (elf64 ? 6 : 14)));
				long stringIndex = Integer.toUnsignedLong(data.getInt(position));
				if ((binding != 1 && binding != 2) || stringIndex >= stringSize
						|| (sectionIndex != 0 && visibility != 0 && visibility != 3)) {
					continue;
				}
				int nameStart = (int) (stringOffset + stringIndex);
				int end = nameStart;
				int limit = (int) Math.min(stringOffset + stringSize, nameStart + 512L);
				while (end < limit && bytes[end] >= 32 && bytes[end] < 127) {
					end++;
				}
				if (end > nameStart && end < limit && bytes[end] == 0) {
					library.symbol(new String(bytes, nameStart, end - nameStart, StandardCharsets.US_ASCII), sectionIndex == 0);
				}
			}
		}
		if (!found) {
			library.limit("elf_symbols_unavailable");
		}
	}

	private static boolean range(long offset, long length, int size) {
		return offset >= 0 && length >= 0 && offset <= size && length <= size - offset;
	}

	private static String display(String value) {
		String clean = value.replaceAll("[\\p{Cntrl}]", " ");
		return clean.length() > 768 ? clean.substring(0, 768) : clean;
	}
}
