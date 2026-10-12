package jadx.plugins.input.java;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.zip.ZipReader;

import static org.assertj.core.api.Assertions.assertThat;

class NestedArchiveTest {

	@TempDir
	Path temp;

	@Test
	void createsPluginTempDirectoryForArchivesInsideAnApk() throws Exception {
		byte[] classBytes;
		try (InputStream input = getClass().getResourceAsStream("NestedArchiveTest.class")) {
			classBytes = input.readAllBytes();
		}
		byte[] inner = archive("NestedArchiveTest.class", classBytes);
		Path apk = temp.resolve("download.apk.1");
		Files.write(apk, archive("assets/nested.zip", inner));
		Path pluginTemp = temp.resolve("plugins-data/java-input");
		assertThat(pluginTemp).doesNotExist();
		JavaInputLoader loader = new JavaInputLoader(new ZipReader(), pluginTemp);
		List<JavaClassReader> classes = loader.collectFiles(List.of(apk));
		assertThat(classes).hasSize(1);
		assertThat(pluginTemp).isDirectory();
		try (var files = Files.list(pluginTemp)) {
			assertThat(files).isEmpty();
		}
	}

	private static byte[] archive(String name, byte[] bytes) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			zip.putNextEntry(new ZipEntry(name));
			zip.write(bytes);
			zip.closeEntry();
		}
		return output.toByteArray();
	}
}
