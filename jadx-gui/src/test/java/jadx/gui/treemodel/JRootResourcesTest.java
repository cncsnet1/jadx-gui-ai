package jadx.gui.treemodel;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.gui.treemodel.JResource.JResType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class JRootResourcesTest {

	@TempDir
	Path temp;

	@Test
	void keepsSameNamedDirectoriesSeparateAndUsesResourceAliases() throws Exception {
		Path archive = temp.resolve("resources.apk");
		try (ZipOutputStream zip = zip(archive)) {
			entry(zip, "assets/a/shared/one.txt");
			entry(zip, "assets/b/shared/two.txt");
			entry(zip, "assets/a/shared/three.txt");
			entry(zip, "old.txt");
		}
		try (JadxDecompiler decompiler = new JadxDecompiler()) {
			decompiler.getArgs().setInputFile(archive.toFile());
			decompiler.load();
			List<ResourceFile> resources = decompiler.getResources();
			resources.stream().filter(r -> r.getOriginalName().equals("old.txt")).findFirst().orElseThrow()
					.setDeobfName("res/raw/renamed.txt");
			JResource root = JRoot.getHierarchyResources(resources);
			assertThat(child(root, "assets", "a", "shared").getSubNodes())
					.extracting(JResource::makeString).containsExactly("one.txt", "three.txt");
			assertThat(child(root, "assets", "b", "shared").getSubNodes())
					.extracting(JResource::makeString).containsExactly("two.txt");
			assertThat(child(root, "res", "raw").getSubNodes()).singleElement()
					.satisfies(r -> assertThat(r.getResFile().getDeobfName()).isEqualTo("res/raw/renamed.txt"));
		}
	}

	@Test
	void fileAndDirectoryWithTheSameNameDoNotMerge() throws Exception {
		Path archive = temp.resolve("collision.apk");
		try (ZipOutputStream zip = zip(archive)) {
			entry(zip, "assets/shared");
			entry(zip, "assets/shared/value.txt");
		}
		try (JadxDecompiler decompiler = new JadxDecompiler()) {
			decompiler.getArgs().setInputFile(archive.toFile());
			decompiler.load();
			JResource root = JRoot.getHierarchyResources(decompiler.getResources());
			assertThat(child(root, "assets").getSubNodes()).extracting(JResource::getType)
					.containsExactly(JResType.DIR, JResType.FILE);
			assertThat(child(root, "assets", "shared").getChildCount()).isEqualTo(1);
		}
	}

	@Test
	void standaloneResourcesUseOnlyTheirFilename() throws Exception {
		try (JadxDecompiler decompiler = new JadxDecompiler()) {
			ResourceFile resource = ResourceFile.createResourceFile(decompiler, temp.resolve("file.txt").toFile(), ResourceType.UNKNOWN);
			JResource root = JRoot.getHierarchyResources(List.of(resource));
			assertThat(root.getSubNodes()).singleElement().satisfies(r -> {
				assertThat(r.getType()).isEqualTo(JResType.FILE);
				assertThat(r.makeString()).isEqualTo("file.txt");
			});
		}
	}

	@Test
	void handlesWideResourceDirectoriesWithoutQuadraticSiblingScans() throws Exception {
		Path archive = temp.resolve("wide.apk");
		int count = 50_000;
		try (ZipOutputStream zip = zip(archive)) {
			for (int i = 0; i < count; i++) {
				entry(zip, "assets/folder" + i + "/value.txt");
			}
		}
		try (JadxDecompiler decompiler = new JadxDecompiler()) {
			decompiler.getArgs().setInputFile(archive.toFile());
			decompiler.load();
			List<ResourceFile> resources = decompiler.getResources();
			JResource root = assertTimeout(Duration.ofSeconds(5), () -> JRoot.getHierarchyResources(resources));
			assertThat(child(root, "assets").getChildCount()).isEqualTo(count);
		}
	}

	private static ZipOutputStream zip(Path path) throws IOException {
		return new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
	}

	private static void entry(ZipOutputStream zip, String name) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(1);
		zip.closeEntry();
	}

	private static JResource child(JResource root, String... path) {
		JResource current = root;
		for (String name : path) {
			current = current.getSubNodes().stream()
					.filter(r -> r.getType() == JResType.DIR && r.getName().equals(name)).findFirst().orElseThrow();
		}
		return current;
	}
}
