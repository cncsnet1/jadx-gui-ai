package jadx.gui.burp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.gui.mcp.McpConnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BurpSetupTest {
	@TempDir
	Path temp;

	@Test
	void keepsPathsWithSpacesAsSeparateArgumentsAndUsesReadOnlyProfile() throws Exception {
		Path java = Files.createFile(temp.resolve("java executable"));
		Path jar = Files.createFile(temp.resolve("official proxy.jar"));
		McpConnection config = BurpSetup.configuration(java.toString(), jar.toString(), "http://127.0.0.1:9876");
		assertThat(config.command).isEqualTo(java.toString());
		assertThat(config.args).containsExactly("-jar", jar.toString(), "--sse-url", "http://127.0.0.1:9876");
		assertThat(config.transport).isEqualTo("stdio");
		assertThat(config.toolProfile).isEqualTo("burp-readonly");
	}

	@Test
	void rejectsRemoteOrAmbiguousAddressesAndMissingProxyFiles() throws Exception {
		Path java = Files.createFile(temp.resolve("java"));
		Path jar = Files.createFile(temp.resolve("proxy.jar"));
		for (String endpoint : List.of("http://burp.example.test:9876", "http://localhost.evil.test:9876",
				"http://user:password@localhost:9876", "http://localhost:9876?token=secret", "http://localhost:0", "file:///tmp")) {
			assertThatThrownBy(() -> BurpSetup.configuration(java.toString(), jar.toString(), endpoint))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> BurpSetup.configuration(java.toString(), temp.resolve("missing.jar").toString(), "http://localhost:9876"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
