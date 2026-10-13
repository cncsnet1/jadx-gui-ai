package jadx.gui.ai.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jadx.api.ResourceFile;
import jadx.zip.IZipEntry;

/**
 * Resolves only unique, already loaded resources; never reads files or discards archive identity.
 */
final class ResourceEvidenceResolver {
	private ResourceEvidenceResolver() {
	}

	static String title(ResourceFile resource, List<Path> inputs) {
		IZipEntry entry = resource.getZipEntry();
		if (entry == null || entry.getZipFile() == null) {
			return resource.getDeobfName();
		}
		List<String> labels = archiveLabels(inputs).get(entry.getZipFile().toPath().toAbsolutePath().normalize());
		return labels == null || labels.isEmpty() ? resource.getDeobfName() : qualified(labels.get(0), entry.getName());
	}

	static ResourceFile resolve(String title, List<Path> inputs, List<ResourceFile> resources) {
		boolean archiveQualified = title.contains("!/");
		Map<Path, List<String>> archives = archiveQualified ? archiveLabels(inputs) : Map.of();
		ResourceFile match = null;
		for (ResourceFile resource : resources) {
			boolean matches = false;
			if (archiveQualified) {
				IZipEntry entry = resource.getZipEntry();
				if (entry != null && entry.getZipFile() != null) {
					List<String> labels = archives.getOrDefault(entry.getZipFile().toPath().toAbsolutePath().normalize(), List.of());
					for (String label : labels) {
						matches |= matches(title, qualified(label, entry.getName()))
								|| matches(title, qualified(label, resource.getOriginalName()))
								|| matches(title, qualified(label, resource.getDeobfName()));
					}
				}
			} else {
				matches = matches(title, resource.getOriginalName()) || matches(title, resource.getDeobfName())
						|| matches(title, display(resource.getOriginalName())) || matches(title, display(resource.getDeobfName()));
			}
			if (matches) {
				if (match != null && match != resource) {
					return null;
				}
				match = resource;
			}
		}
		return match;
	}

	private static Map<Path, List<String>> archiveLabels(List<Path> inputs) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<Path, List<String>> labels = new LinkedHashMap<>();
		for (Path input : inputs) {
			String base = display(input.getFileName() == null ? "input" : input.getFileName().toString());
			int occurrence = counts.merge(base, 1, Integer::sum);
			String label = occurrence == 1 ? base : base + " [" + occurrence + ']';
			labels.computeIfAbsent(input.toAbsolutePath().normalize(), ignored -> new ArrayList<>()).add(label);
		}
		return labels;
	}

	private static String qualified(String archive, String resource) {
		return display(archive + "!/" + display(resource.replace('\\', '/')));
	}

	private static boolean matches(String title, String name) {
		return !name.isEmpty() && (title.equals(name) || title.startsWith(name + ':'));
	}

	private static String display(String value) {
		String clean = value.replaceAll("[\\p{Cntrl}]", " ");
		return clean.length() > 768 ? clean.substring(0, 768) : clean;
	}
}
