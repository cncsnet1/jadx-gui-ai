package jadx.gui.ai.analysis;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.zip.IZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceEvidenceResolverTest {
	@Test
	void qualifiedEvidenceUsesArchiveIdentityAndLegacyEvidenceRequiresUniqueMatch() {
		Path base = Path.of("fixture", "base.apk");
		Path split = Path.of("fixture", "split.apk");
		ResourceFile first = resource(base, "assets/app.js");
		ResourceFile second = resource(split, "assets/app.js");
		List<Path> inputs = List.of(base, split);
		List<ResourceFile> resources = List.of(first, second);
		assertThat(ResourceEvidenceResolver.resolve("split.apk!/assets/app.js", inputs, resources)).isSameAs(second);
		assertThat(ResourceEvidenceResolver.resolve("base.apk!/assets/app.js:12:100 · fetch", inputs, resources)).isSameAs(first);
		assertThat(ResourceEvidenceResolver.resolve("assets/app.js:12", inputs, resources)).isNull();
		assertThat(ResourceEvidenceResolver.resolve("other.apk!/assets/app.js", inputs, resources)).isNull();
		assertThat(ResourceEvidenceResolver.resolve("assets/app.js:12", inputs, List.of(second))).isSameAs(second);
	}

	@Test
	void duplicateArchiveNamesUseInputOrderAndRenamedResourcesKeepTheirOriginalIdentity() {
		Path firstPath = Path.of("one", "app.apk");
		Path secondPath = Path.of("two", "app.apk");
		ResourceFile first = resource(firstPath, "res/raw/a.js");
		ResourceFile second = resource(secondPath, "res/raw/a.js");
		second.setDeobfName("res/raw/renamed.js");
		List<Path> inputs = List.of(firstPath, secondPath);
		List<ResourceFile> resources = List.of(second, first);
		assertThat(ResourceEvidenceResolver.resolve("app.apk [2]!/res/raw/a.js", inputs, resources)).isSameAs(second);
		assertThat(ResourceEvidenceResolver.resolve("app.apk!/res/raw/a.js", inputs, resources)).isSameAs(first);
		assertThat(ResourceEvidenceResolver.resolve("res/raw/renamed.js:3", inputs, resources)).isSameAs(second);
		assertThat(ResourceEvidenceResolver.resolve("res/raw/a.js:3", inputs, resources)).isNull();
	}

	@Test
	void scannerDisplayNormalizationAndTruncationNeverSelectAnAmbiguousSource() {
		Path archive = Path.of("app.apk");
		ResourceFile controlled = resource(archive, "assets/a\tb.js");
		assertThat(ResourceEvidenceResolver.resolve("app.apk!/assets/a b.js", List.of(archive), List.of(controlled))).isSameAs(controlled);
		String prefix = "assets/" + "segment/".repeat(100);
		ResourceFile first = resource(archive, prefix + "one.js");
		ResourceFile second = resource(archive, prefix + "two.js");
		String truncatedTitle = ("app.apk!/" + prefix + "one.js").substring(0, 768);
		assertThat(ResourceEvidenceResolver.resolve(truncatedTitle, List.of(archive), List.of(first, second))).isNull();
	}

	private static ResourceFile resource(Path archive, String name) {
		return new ResourceFile(null, name, ResourceType.UNKNOWN) {
			@Override
			public IZipEntry getZipEntry() {
				return new IZipEntry() {
					@Override
					public String getName() {
						return name;
					}

					@Override
					public byte[] getBytes() {
						throw new AssertionError("Resolver must not read files");
					}

					@Override
					public InputStream getInputStream() {
						throw new AssertionError("Resolver must not open files");
					}

					@Override
					public long getCompressedSize() {
						return 0;
					}

					@Override
					public long getUncompressedSize() {
						return 0;
					}

					@Override
					public boolean isDirectory() {
						return false;
					}

					@Override
					public File getZipFile() {
						return archive.toFile();
					}

					@Override
					public boolean preferBytes() {
						return false;
					}
				};
			}
		};
	}
}
