package jadx.gui.burp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import jadx.gui.ai.AiCancellation;
import jadx.gui.mcp.McpClient;
import jadx.gui.mcp.McpConnection;
import jadx.gui.mcp.McpToolProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BurpBridgeTest {
	private static final URI ORIGIN = URI.create("https://api.example.test");

	static McpConnection fixture(String mode) throws Exception {
		McpConnection config = new McpConnection();
		config.command = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String test = Path.of(BurpProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		String main = Path.of(BurpBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		String gson = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		config.args = List.of("-Dfile.encoding=UTF-8", "-cp", test + File.pathSeparator + main + File.pathSeparator + gson,
				BurpProcessFixture.class.getName(), mode);
		config.toolProfile = "burp-readonly";
		return config;
	}

	@Test
	void officialSchemasWorkOverRealStdioAndNeverInvokeSendTools() throws Exception {
		try (McpClient client = McpClient.connect(fixture("normal"), new AiCancellation())) {
			assertThat(McpToolProfiles.select("burp-readonly", client.tools())).extracting(t -> t.get("name").getAsString())
					.containsExactly(BurpBridge.HISTORY);
			BurpBridge.Page page = BurpBridge.read(client, ORIGIN, 0, new AiCancellation());
			assertThat(page.exchanges()).hasSize(3);
			BurpExchange request = page.exchanges().get(0);
			BurpBridge.stage(client, request, ORIGIN, new AiCancellation());
			JsonObject stats = client.call("fixture_stats", new JsonObject(), new AiCancellation());
			assertThat(stats.get("reads").getAsInt()).isEqualTo(1);
			assertThat(stats.get("staged").getAsInt()).isEqualTo(1);
			assertThat(stats.getAsJsonObject("arguments").get("content").getAsString()).isEqualTo(request.rawRequest());
		}
	}

	@Test
	void incompatibleSchemaIsRejectedBeforeCallingTheTool() throws Exception {
		try (McpClient client = McpClient.connect(fixture("schema"), new AiCancellation())) {
			assertThatThrownBy(() -> BurpBridge.read(client, ORIGIN, 0, new AiCancellation())).isInstanceOf(IOException.class);
			assertThat(client.call("fixture_stats", new JsonObject(), new AiCancellation()).get("reads").getAsInt()).isZero();
		}
	}

	@Test
	void denialIsNotAnEmptyHistoryAndUnknownStageResultIsNotRetried() throws Exception {
		try (McpClient client = McpClient.connect(fixture("deny"), new AiCancellation())) {
			assertThatThrownBy(() -> BurpBridge.read(client, ORIGIN, 0, new AiCancellation())).isInstanceOf(IOException.class);
		}
		try (McpClient client = McpClient.connect(fixture("unknown"), new AiCancellation())) {
			BurpExchange request = BurpBridge.read(client, ORIGIN, 0, new AiCancellation()).exchanges().get(0);
			assertThatThrownBy(() -> BurpBridge.stage(client, request, ORIGIN, new AiCancellation())).isInstanceOf(IOException.class);
			assertThat(client.call("fixture_stats", new JsonObject(), new AiCancellation()).get("staged").getAsInt()).isEqualTo(1);
		}
	}

	@Test
	void pagesThroughHistoryAndCanCancelBlockedRead() throws Exception {
		try (McpClient client = McpClient.connect(fixture("pages"), new AiCancellation())) {
			BurpBridge.Page first = BurpBridge.read(client, ORIGIN, 0, new AiCancellation());
			assertThat(first.end()).isFalse();
			assertThat(BurpBridge.read(client, ORIGIN, first.nextOffset(), new AiCancellation()).end()).isTrue();
		}
		try (McpClient client = McpClient.connect(fixture("wait"), new AiCancellation())) {
			AiCancellation token = new AiCancellation();
			CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
				try {
					BurpBridge.read(client, ORIGIN, 0, token);
					return false;
				} catch (InterruptedException | IOException e) {
					return true;
				}
			});
			Thread.sleep(120);
			token.cancel();
			assertThat(result.get(3, TimeUnit.SECONDS)).isTrue();
			assertThat(client.isClosed()).isTrue();
		}
	}
}
