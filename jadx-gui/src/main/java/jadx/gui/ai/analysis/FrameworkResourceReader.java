package jadx.gui.ai.analysis;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourcesLoader;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiEvidence;

/** Reads only bounded text from resources already belonging to the loaded project. */
final class FrameworkResourceReader {
	private static final int MAX_RESOURCES = 128;
	private static final int MAX_REGISTERED_RESOURCES = 384;
	private static final int MAX_CHARACTERS = 1024 * 1024;
	private static final int PAGE_SIZE = 6000;
	private static final Pattern TARGET = Pattern.compile("(R-[0-9a-f]{16})(?:@([0-9]{1,7}))?");

	static final class Entry {
		final String id;
		final String title;
		final ResourceFile resource;

		Entry(String title, ResourceFile resource) {
			this.id = "R-" + AiEvidence.fingerprint(title).substring(0, 16);
			this.title = title;
			this.resource = resource;
		}
	}

	static final class Page {
		final AiEvidence evidence;
		final String next;
		final int offset;
		final boolean decodedText;

		Page(AiEvidence evidence, String next, int offset) {
			this(evidence, next, offset, true);
		}

		Page(AiEvidence evidence, String next, int offset, boolean decodedText) {
			this.evidence = evidence;
			this.next = next;
			this.offset = offset;
			this.decodedText = decodedText;
		}
	}

	private FrameworkResourceReader() {
	}

	static List<Entry> catalog(JadxDecompiler decompiler, AnalysisReport report) {
		return catalog(decompiler, report, List.of());
	}

	static List<Entry> catalog(JadxDecompiler decompiler, AnalysisReport report, List<AiEvidence> selectedSources) {
		List<Path> inputs = decompiler.getArgs().getInputFiles().stream().map(java.io.File::toPath).toList();
		List<ResourceFile> resources = decompiler.getResources();
		Map<String, Entry> result = new LinkedHashMap<>();
		for (AiEvidence source : selectedSources) {
			add(result, ResourceEvidenceResolver.resolve(source.getTitle(), inputs, resources), inputs, resources, MAX_RESOURCES);
		}
		for (FrameworkAnalysis.Finding finding : report.getFrameworkAnalysis().getFindings()) {
			for (AiEvidence source : finding.getEvidence()) {
				ResourceFile resource = ResourceEvidenceResolver.resolve(source.getTitle(), inputs, resources);
				add(result, resource, inputs, resources, MAX_RESOURCES);
			}
		}
		List<ResourceFile> ordered = resources.stream().filter(FrameworkResourceReader::supported)
				.sorted(java.util.Comparator.comparing(ResourceFile::getOriginalName)).toList();
		for (ResourceFile resource : ordered) {
			if (result.size() >= MAX_RESOURCES) {
				break;
			}
			add(result, resource, inputs, resources, MAX_RESOURCES);
		}
		return new ArrayList<>(result.values());
	}

	/**
	 * Rebuild saved identifiers by uniquely resolving already loaded resources, without reading bytes.
	 */
	static void restore(JadxDecompiler decompiler, List<Entry> catalog, List<String> savedTitles) {
		List<Path> inputs = decompiler.getArgs().getInputFiles().stream().map(java.io.File::toPath).toList();
		List<ResourceFile> resources = decompiler.getResources();
		Map<String, Entry> registered = new LinkedHashMap<>();
		catalog.forEach(entry -> registered.put(entry.id, entry));
		for (String title : savedTitles.stream().limit(MAX_REGISTERED_RESOURCES).toList()) {
			add(registered, ResourceEvidenceResolver.resolve(title, inputs, resources), inputs, resources, MAX_REGISTERED_RESOURCES);
		}
		catalog.clear();
		catalog.addAll(registered.values());
	}

	/**
	 * Search names in the loaded project, registering only unambiguous resources for later bounded
	 * reads.
	 */
	static List<Entry> search(JadxDecompiler decompiler, List<Entry> catalog, String query, int limit,
			AiCancellation token) throws InterruptedException {
		List<Path> inputs = decompiler.getArgs().getInputFiles().stream().map(java.io.File::toPath).toList();
		List<ResourceFile> resources = decompiler.getResources();
		Map<String, Entry> registered = new LinkedHashMap<>();
		catalog.forEach(entry -> registered.put(entry.id, entry));
		List<Entry> matches = new ArrayList<>();
		String needle = query.toLowerCase(Locale.ROOT);
		for (ResourceFile resource : resources) {
			token.check();
			if (matches.size() >= limit) {
				break;
			}
			if (!supported(resource)) {
				continue;
			}
			String title = ResourceEvidenceResolver.title(resource, inputs);
			if ((title + '\n' + resource.getOriginalName() + '\n' + resource.getDeobfName())
					.toLowerCase(Locale.ROOT).contains(needle)) {
				Entry entry = add(registered, resource, inputs, resources, MAX_REGISTERED_RESOURCES);
				if (entry != null && !matches.contains(entry)) {
					matches.add(entry);
				}
			}
		}
		catalog.clear();
		catalog.addAll(registered.values());
		return matches;
	}

	private static Entry add(Map<String, Entry> result, ResourceFile resource, List<Path> inputs, List<ResourceFile> resources,
			int limit) {
		if (resource == null || !supported(resource)) {
			return null;
		}
		String title = ResourceEvidenceResolver.title(resource, inputs);
		Entry entry = new Entry(title, resource);
		Entry existing = result.get(entry.id);
		if (existing != null) {
			return existing.resource == resource ? existing : null;
		}
		if (result.size() >= limit) {
			return null;
		}
		if (ResourceEvidenceResolver.resolve(title, inputs, resources) == resource) {
			result.put(entry.id, entry);
			return entry;
		}
		return null;
	}

	private static boolean supported(ResourceFile resource) {
		String name = resource.getOriginalName().toLowerCase(Locale.ROOT);
		return name.endsWith(".js") || name.endsWith(".json") || name.endsWith(".html") || name.endsWith(".htm")
				|| name.endsWith(".vue") || name.endsWith(".nvue") || name.endsWith(".bundle") || name.endsWith(".hbc");
	}

	static Page read(List<Entry> catalog, String target, AiCancellation token) throws InterruptedException {
		Matcher matcher = TARGET.matcher(target);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Use a resource R- ID from the catalog, optionally followed by @characterOffset");
		}
		int offset = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
		if (offset >= MAX_CHARACTERS) {
			throw new IllegalArgumentException("Resource reads cover at most the first 1048576 decoded UTF-16 characters");
		}
		Entry entry = catalog.stream().filter(e -> e.id.equals(matcher.group(1))).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown resource ID; use the current project catalog"));
		token.check();
		try {
			return ResourcesLoader.decodeStream(entry.resource, (size, input) -> {
				byte[] prefix = input.readNBytes(8192);
				if (!ScriptContent.isText(prefix)) {
					String format = ScriptContent.isHermes(prefix) ? ScriptContent.hermesDescription(prefix) : "Binary resource observed.";
					AiEvidence header =
							new AiEvidence(null, entry.title, format + " No UTF-8 source was decoded; a compatible decoder is required.",
									AiEvidence.fingerprint(format), true);
					return new Page(header, "", offset, false);
				}
				InputStreamReader reader = new InputStreamReader(new java.io.SequenceInputStream(
						new java.io.ByteArrayInputStream(prefix), input), StandardCharsets.UTF_8);
				char[] buffer = new char[4096];
				int skipped = 0;
				int line = 1;
				while (skipped < offset) {
					check(token);
					int count = reader.read(buffer, 0, Math.min(buffer.length, offset - skipped));
					if (count < 0) {
						throw new IllegalArgumentException("Resource offset exceeds its decoded content");
					}
					for (int i = 0; i < count; i++) {
						if (buffer[i] == '\n') {
							line++;
						}
					}
					skipped += count;
				}
				int limit = Math.min(PAGE_SIZE, MAX_CHARACTERS - offset);
				StringBuilder text = new StringBuilder();
				while (text.length() < limit) {
					check(token);
					int count = reader.read(buffer, 0, Math.min(buffer.length, limit - text.length()));
					if (count < 0) {
						break;
					}
					text.append(buffer, 0, count);
				}
				check(token);
				boolean more = reader.read() >= 0;
				String next = more && offset + text.length() < MAX_CHARACTERS ? entry.id + '@' + (offset + text.length()) : "";
				String source = text.toString();
				AiEvidence evidence = new AiEvidence(null, entry.title + ':' + line + ':' + offset, source,
						AiEvidence.fingerprint(source), offset > 0 || more);
				return new Page(evidence, next, offset);
			});
		} catch (Exception e) {
			token.check();
			throw new IllegalArgumentException("Could not read the requested resource page", e);
		}
	}

	private static void check(AiCancellation token) throws IOException {
		if (token.isCancelled() || Thread.currentThread().isInterrupted()) {
			throw new java.io.InterruptedIOException("Resource read cancelled");
		}
	}
}
