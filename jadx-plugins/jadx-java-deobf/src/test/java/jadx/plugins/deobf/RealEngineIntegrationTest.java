package jadx.plugins.deobf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in integration check; the test never downloads an engine or executes the sample. */
@EnabledIfEnvironmentVariable(named = "JADX_DEOBF_TEST_ENGINE", matches = ".+")
class RealEngineIntegrationTest {
	@TempDir
	Path temp;

	@Test
	void detectsAndDecryptsAnAllatoriStyleStringWithTheExternalEngine() throws Exception {
		Path source = temp.resolve("Example.java");
		Files.writeString(source, "public class Example {"
				+ " public static String message() { return decode(\"IDMMN\"); }"
				+ " private static String decode(String s) { char[] chars = new char[s.length()];"
				+ " for (int i = 0; i < chars.length; i++) { chars[i] = (char) (s.charAt(i) ^ 1); }"
				+ " return new String(chars); } }");
		assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null,
				"--release", "8", "-d", temp.toString(), source.toString())).isZero();
		Path input = temp.resolve("encrypted.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
			zip.putNextEntry(new ZipEntry("Example.class"));
			zip.write(Files.readAllBytes(temp.resolve("Example.class")));
			zip.closeEntry();
		}
		byte[] original = Files.readAllBytes(input);
		JadxArgs args = new JadxArgs();
		args.setInputFile(input.toFile());
		args.setPluginOptions(Map.of("java-deobf.engine", System.getenv("JADX_DEOBF_TEST_ENGINE"), "java-deobf.mode", "auto"));
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();
			assertThat(jadx.getClasses()).hasSize(1);
			assertThat(jadx.getClasses().get(0).getCode()).contains("return \"HELLO\";").doesNotContain("IDMMN");
			assertThat(Files.readAllBytes(input)).isEqualTo(original);
		}
	}
}
