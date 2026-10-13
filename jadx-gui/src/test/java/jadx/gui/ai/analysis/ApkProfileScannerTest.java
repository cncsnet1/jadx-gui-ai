package jadx.gui.ai.analysis;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.analysis.ApkProfile.Confidence;
import jadx.gui.ai.analysis.ApkProfile.Fact;
import jadx.gui.ai.fixtures.AnalysisFixture;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApkProfileScannerTest {
	@TempDir
	Path temporary;

	@Test
	void profilesRenamedApkAndSplitInputWithoutExtractingFiles() throws Exception {
		Path base = zip("应用.apk.1", Map.of("AndroidManifest.xml", bytes("manifest"), "classes.dex", bytes("dex\n035\0"),
				"assets/app.js", bytes("const password='should-not-be-read';")));
		Path split = zip("no-extension", Map.of("lib/arm64-v8a/libclient.so", elf(true, ByteOrder.LITTLE_ENDIAN)));
		ApkProfile profile = ApkProfileScanner.scan(List.of(base, split), new AiCancellation());
		assertThat(profile.isComplete()).isTrue();
		assertThat(profile.getArchiveCount()).isEqualTo(2);
		assertThat(profile.getDexCount()).isEqualTo(1);
		assertThat(profile.getJavaScriptCount()).isEqualTo(1);
		assertThat(profile.getNativeLibraryCount()).isEqualTo(1);
		assertThat(profile.getFacts()).extracting(Fact::getCode)
				.contains("protection_unknown", "technology_android", "visibility_dex", "visibility_javascript", "visibility_native");
		assertThat(profile.getFacts().get(0).getConfidence()).isEqualTo(Confidence.UNKNOWN);
		assertThat(defaultGsonBuilder().create().toJson(profile)).doesNotContain("should-not-be-read", temporary.toString(),
				temporary.toString().replace("\\", "\\\\"));
		assertThat(profile.getEvidence()).anyMatch(e -> e.getTitle().contains("应用.apk.1!/classes.dex"));
		try (var files = Files.list(temporary)) {
			assertThat(files.count()).isEqualTo(2);
		}
	}

	@Test
	void doesNotMistakeFlutterAotForProtectionOrOneSdkForUniApp() throws Exception {
		Path input = zip("flutter.apk",
				Map.of("classes.dex", bytes("dex"), "lib/arm64-v8a/libflutter.so", elf(true, ByteOrder.LITTLE_ENDIAN),
						"lib/arm64-v8a/libapp.so", elf(true, ByteOrder.LITTLE_ENDIAN), "assets/flutter_assets/AssetManifest.bin",
						new byte[0]));
		ApkProfile profile = ApkProfileScanner.scan(List.of(input), List.of("io.dcloud.feature.sdk.DCUniMPSDK"), new AiCancellation());
		assertThat(profile.getFacts()).extracting(Fact::getCode)
				.contains("technology_flutter", "technology_dcloud", "protection_unknown")
				.doesNotContain("protection_marker", "technology_uniapp");
		assertThat(profile.getRecommendations()).contains("inspect_native");
		Path weak = zip("single-marker.apk", Map.of("lib/arm64-v8a/libapp.so", new byte[0], "assets/index.android.bundle", new byte[0]));
		assertThat(ApkProfileScanner.scan(List.of(weak), new AiCancellation()).getFacts()).extracting(Fact::getCode)
				.doesNotContain("technology_flutter", "technology_react_native");
	}

	@Test
	void corroboratesUniAppReactNativeAndHybridWebViewWithoutAssumingExclusiveStack() throws Exception {
		Path uni = zip("uni.apk", Map.of("assets/apps/__UNI__/www/app-service.js", bytes("uni.request({url:'/x'})"),
				"assets/data/dcloud_control.xml", bytes("control")));
		ApkProfile uniProfile = ApkProfileScanner.scan(List.of(uni), new AiCancellation());
		assertThat(uniProfile.getFacts()).extracting(Fact::getCode).contains("technology_uniapp");
		assertThat(uniProfile.getRecommendations()).contains("inspect_javascript");
		Path react = zip("hybrid.apk", Map.of("assets/index.android.bundle", bytes("bundle"), "assets/www/index.html", bytes("<html>")));
		ApkProfile hybrid = ApkProfileScanner.scan(List.of(react),
				List.of("com.facebook.react.ReactActivity", "org.apache.cordova.CordovaActivity"), new AiCancellation());
		assertThat(hybrid.getFacts()).extracting(Fact::getCode).contains("technology_react_native", "technology_webview");
	}

	@Test
	void protectionMarkersRemainSuspicionsAndRetainTheirLocations() throws Exception {
		Path input = zip("protected.apk", Map.of("lib/arm64-v8a/libjiagu.so", elf(true, ByteOrder.LITTLE_ENDIAN),
				"classes.dex", bytes("dex")));
		ApkProfile profile = ApkProfileScanner.scan(List.of(input), List.of("com.stub.StubApp"), new AiCancellation());
		Fact fact = profile.getFacts().stream().filter(f -> f.getCode().equals("protection_marker")).findFirst().orElseThrow();
		assertThat(fact.getValue()).isEqualTo("360 Jiagu");
		assertThat(fact.getConfidence()).isEqualTo(Confidence.SUSPECTED);
		assertThat(fact.getEvidence()).hasSize(2).anyMatch(e -> e.getTitle().endsWith("libjiagu.so"));
		assertThat(profile.getRecommendations()).startsWith("check_protection");
		Path generic = zip("generic.apk", Map.of("lib/arm64-v8a/libexec.so", new byte[0]));
		assertThat(ApkProfileScanner.scan(List.of(generic), new AiCancellation()).getFacts())
				.noneMatch(f -> f.getCode().equals("protection_marker"));
	}

	@Test
	void engineLibrariesAloneDoNotBecomeApplicationRoutesAndHermesRequiresItsHeader() throws Exception {
		Path engines = zip("sdk-only.apk", Map.of("lib/arm64-v8a/libreactnative.so", new byte[0],
				"lib/arm64-v8a/libhermes.so", new byte[0], "lib/arm64-v8a/libflutter.so", new byte[0],
				"lib/arm64-v8a/libunity.so", new byte[0], "lib/arm64-v8a/libil2cpp.so", new byte[0]));
		assertThat(
				ApkProfileScanner.scan(List.of(engines), List.of("io.flutter.embedding.FlutterActivity"), new AiCancellation()).getFacts())
						.extracting(Fact::getCode).doesNotContain("technology_react_native", "technology_flutter", "technology_unity");
		byte[] hermes = new byte[128];
		ByteBuffer.wrap(hermes).order(ByteOrder.LITTLE_ENDIAN).putLong(0x1F1903C103BC1FC6L).putInt(96);
		Path compiled = zip("hermes.apk", Map.of("assets/program.hbc", hermes));
		ApkProfile profile = ApkProfileScanner.scan(List.of(compiled), List.of("com.facebook.react.ReactActivity"), new AiCancellation());
		assertThat(profile.getFacts()).extracting(Fact::getCode).contains("technology_react_native");
		assertThat(profile.getEvidence()).anyMatch(source -> source.getCode().contains("Hermes execution bytecode magic")
				&& source.getCode().contains("version=96"));
		Path fake = zip("fake-hbc.apk", Map.of("assets/program.hbc", bytes("not a Hermes file")));
		assertThat(ApkProfileScanner.scan(List.of(fake), List.of("com.facebook.react.ReactActivity"), new AiCancellation()).getFacts())
				.extracting(Fact::getCode).doesNotContain("technology_react_native");
	}

	@Test
	void mixedCordovaUnityAndAndroidInventoryKeepsAllCorroboratedRoutes() throws Exception {
		Path input = zip("mixed-routes.apk", Map.of("classes.dex", bytes("dex"), "assets/www/index.html", bytes("<html/>"),
				"assets/www/cordova.js", bytes("runtime"), "res/xml/config.xml", bytes("<widget/>"),
				"lib/arm64-v8a/libunity.so", elf(true, ByteOrder.LITTLE_ENDIAN),
				"lib/arm64-v8a/libil2cpp.so", elf(true, ByteOrder.LITTLE_ENDIAN),
				"assets/bin/Data/Managed/Metadata/global-metadata.dat", bytes("metadata"),
				"assets/bin/Data/globalgamemanagers", bytes("unity data")));
		ApkProfile profile = ApkProfileScanner.scan(List.of(input), List.of("org.apache.cordova.CordovaActivity"), new AiCancellation());
		assertThat(profile.getFacts()).extracting(Fact::getCode).contains("technology_cordova", "technology_webview", "technology_unity",
				"technology_android");
		assertThat(profile.getEvidence()).anyMatch(source -> source.getTitle().endsWith("globalgamemanagers"));
	}

	@Test
	void indexesElf32And64AcrossBothByteOrders() throws Exception {
		for (boolean elf64 : List.of(false, true)) {
			for (ByteOrder order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
				Path input = zip("native-" + elf64 + '-' + order + ".apk", Map.of("lib/test/libjni.so", elf(elf64, order)));
				ApkProfile.NativeLibrary library = ApkProfileScanner.scan(List.of(input), new AiCancellation()).getNativeLibraries().get(0);
				assertThat(library.isElf()).isTrue();
				assertThat(library.getBitness()).isEqualTo(elf64 ? 64 : 32);
				assertThat(library.getArchitecture()).isEqualTo(elf64 ? "AArch64" : "ARM");
				assertThat(library.getImportedSymbols()).containsExactly("socket");
				assertThat(library.getExportedSymbols()).containsExactly("Java_demo_Client_sign", "JNI_OnLoad");
				assertThat(library.getJniSymbols()).containsExactly("Java_demo_Client_sign", "JNI_OnLoad");
				assertThat(library.getLimitations()).isEmpty();
				assertThat(library.getEvidence().get(0).getCode()).contains("RegisterNatives", "not resolved");
			}
		}
	}

	@Test
	void corruptElfAndZipDoNotDiscardOtherInputsAndHugeOffsetsAreRejected() throws Exception {
		byte[] invalidOffset = elf(true, ByteOrder.LITTLE_ENDIAN);
		ByteBuffer.wrap(invalidOffset).order(ByteOrder.LITTLE_ENDIAN).putLong(40, Long.MAX_VALUE);
		Path input = zip("invalid-libraries.apk", Map.of("lib/x86/libbad.so", bytes("not ELF"), "lib/x86/liboffset.so", invalidOffset));
		Path broken = Files.write(temporary.resolve("broken.apk.1"), bytes("PK\003\004broken"));
		ApkProfile profile = ApkProfileScanner.scan(List.of(broken, input, temporary.resolve("missing.apk")), new AiCancellation());
		assertThat(profile.isComplete()).isFalse();
		assertThat(profile.getArchiveCount()).isEqualTo(1);
		assertThat(profile.getGaps()).anyMatch(g -> g.startsWith("archive_unreadable"))
				.anyMatch(g -> g.startsWith("input_unavailable"));
		assertThat(profile.getNativeLibraries()).anyMatch(lib -> lib.getLimitations().contains("elf_header_invalid"))
				.anyMatch(lib -> lib.getLimitations().contains("elf_symbols_unavailable"));
	}

	@Test
	void boundedReadsAndLibraryCountPreserveInventoryWithoutInflatingEveryEntry() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("lib/arm64-v8a/liblarge.so", Arrays.copyOf(elf(true, ByteOrder.LITTLE_ENDIAN), 5 * 1024 * 1024));
		for (int i = 0; i < 130; i++) {
			entries.put("lib/arm64-v8a/lib" + i + ".so", new byte[0]);
		}
		ApkProfile profile = ApkProfileScanner.scan(List.of(zip("bounded.apk", entries)), new AiCancellation());
		assertThat(profile.getNativeLibraryCount()).isEqualTo(131);
		assertThat(profile.getNativeLibraries()).hasSize(128);
		assertThat(profile.getNativeLibraries().get(0).getLimitations()).contains("read_limit");
		assertThat(profile.getGaps()).anyMatch(g -> g.startsWith("native_limit"));
	}

	@Test
	void cancelIsPropagatedBeforeOpeningInputs() {
		AiCancellation cancellation = new AiCancellation();
		cancellation.cancel();
		assertThatThrownBy(() -> ApkProfileScanner.scan(List.of(temporary.resolve("missing")), cancellation))
				.isInstanceOf(InterruptedException.class);
	}

	@Test
	void largeJavaScriptResourceKeepsPrefixRequestsAndMarksIncompleteCoverage() throws Exception {
		String script = "uni.request({url:'/prefix',method:'POST'});\n" + " ".repeat(9 * 1024 * 1024)
				+ "fetch('/unread-tail');";
		Path input = zip("large-bundle.apk.1", Map.of("assets/apps/demo/www/app-service.js", bytes(script),
				"assets/help.htm", bytes("<script>fetch('/from-html')</script>")));
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile(), input.toFile()));
		args.setUseDxInput(false);
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("prefix", new AiCancellation(), ignored -> {
			});
			assertThat(report.getApis()).anyMatch(api -> api.getAddress().equals("/prefix") && api.isTruncated())
					.anyMatch(api -> api.getAddress().equals("/from-html"))
					.noneMatch(api -> api.getAddress().equals("/unread-tail"));
			assertThat(report.getGaps()).anyMatch(gap -> gap.contains("app-service.js"));
		}
	}

	@Test
	void profileSurvivesSafeSerializationSnapshotsAndMarkdownWithCacheVersionInvalidation() throws Exception {
		Path input = zip("save.apk", Map.of("classes.dex", bytes("dex"), "lib/arm64-v8a/libjni.so", elf(true, ByteOrder.LITTLE_ENDIAN)));
		AnalysisReport report = new AnalysisReport();
		report.setFingerprint("abc");
		report.setComplete(true);
		report.setProfile(ApkProfileScanner.scan(List.of(input), new AiCancellation()));
		var gson = defaultGsonBuilder().disableJdkUnsafe().create();
		AnalysisReport restored = gson.fromJson(gson.toJson(report.snapshot()), AnalysisReport.class);
		assertThat(restored.getProfile().getDexCount()).isEqualTo(1);
		assertThat(restored.getProfile().getNativeLibraries().get(0).getJniSymbols()).contains("JNI_OnLoad");
		assertThat(restored.markdown()).contains("classes.dex", "Java_demo_Client_sign");
		assertThat(restored.matches("abc")).isTrue();
		AnalysisReport previous = gson.fromJson("{\"fingerprint\":\"abc\",\"complete\":true,\"ruleVersion\":\"3\"}", AnalysisReport.class);
		assertThat(previous.matches("abc")).isFalse();
		assertThat(previous.getProfile().getFacts()).isEmpty();
	}

	private Path zip(String name, Map<String, byte[]> files) throws Exception {
		Path target = temporary.resolve(name);
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
			output.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
			for (Map.Entry<String, byte[]> file : files.entrySet()) {
				output.putNextEntry(new ZipEntry(file.getKey()));
				output.write(file.getValue());
				output.closeEntry();
			}
		}
		return target;
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	/** Minimal independent ELF fixture: null section, string table, dynamic symbol table. */
	private static byte[] elf(boolean elf64, ByteOrder order) {
		byte[] file = new byte[1024];
		ByteBuffer data = ByteBuffer.wrap(file).order(order);
		file[0] = 0x7f;
		file[1] = 'E';
		file[2] = 'L';
		file[3] = 'F';
		file[4] = (byte) (elf64 ? 2 : 1);
		file[5] = (byte) (order == ByteOrder.LITTLE_ENDIAN ? 1 : 2);
		file[6] = 1;
		data.putShort(16, (short) 3);
		data.putShort(18, (short) (elf64 ? 183 : 40));
		int sectionSize = elf64 ? 64 : 40;
		int symbolSize = elf64 ? 24 : 16;
		if (elf64) {
			data.putLong(40, 512);
		} else {
			data.putInt(32, 512);
		}
		data.putShort(elf64 ? 58 : 46, (short) sectionSize);
		data.putShort(elf64 ? 60 : 48, (short) 3);
		int stringSection = 512 + sectionSize;
		data.putInt(stringSection + 4, 3);
		byte[] strings = bytes("\0socket\0Java_demo_Client_sign\0JNI_OnLoad\0");
		System.arraycopy(strings, 0, file, 128, strings.length);
		int symbolSection = stringSection + sectionSize;
		data.putInt(symbolSection + 4, 11);
		if (elf64) {
			data.putLong(stringSection + 24, 128).putLong(stringSection + 32, strings.length);
			data.putLong(symbolSection + 24, 256).putLong(symbolSection + 32, 4L * symbolSize);
			data.putInt(symbolSection + 40, 1).putLong(symbolSection + 56, symbolSize);
		} else {
			data.putInt(stringSection + 16, 128).putInt(stringSection + 20, strings.length);
			data.putInt(symbolSection + 16, 256).putInt(symbolSection + 20, 4 * symbolSize);
			data.putInt(symbolSection + 24, 1).putInt(symbolSection + 36, symbolSize);
		}
		int name = 1;
		for (int index = 1; index < 4; index++) {
			int symbol = 256 + index * symbolSize;
			data.putInt(symbol, name);
			file[symbol + (elf64 ? 4 : 12)] = 0x12; // GLOBAL / FUNC
			data.putShort(symbol + (elf64 ? 6 : 14), (short) (index == 1 ? 0 : 1));
			while (strings[name++] != 0) {
				// Advance to next string.
			}
		}
		return file;
	}
}
