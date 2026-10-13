package jadx.gui.mcp;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import jadx.gui.ai.AiCancellation;
import jadx.gui.burp.BurpBridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSseTransportTest {
	@Test
	void connectsDirectlyReadsHistoryAndStagesRepeaterWithoutAProxyProcess() throws Exception {
		try (McpSseFixture server = new McpSseFixture(0);
				McpClient client = McpClient.connect(server.config(), new AiCancellation())) {
			assertThat(client.configuration().command).isEmpty();
			assertThat(client.configuration().args).isEmpty();
			BurpBridge.Page page = BurpBridge.read(client, URI.create("https://api.example.test"), 0, new AiCancellation());
			assertThat(page.exchanges()).hasSize(3);
			BurpBridge.stage(client, page.exchanges().get(0), URI.create("https://api.example.test"), new AiCancellation());
			assertThat(server.staged).hasValue(1);
			assertThat(server.posts).hasValue(5);
			assertThat(client.isClosed()).isFalse();
		}
	}

	@Test
	void refusesEndpointChangesOutsideTheConfiguredOriginBeforePosting() throws Exception {
		for (String endpoint : List.of("http://localhost:9876/message", "https://127.0.0.1:9876/message",
				"http://user:secret@127.0.0.1:9876/message")) {
			try (McpSseFixture server = new McpSseFixture(0)) {
				server.mode = endpoint;
				assertThatThrownBy(() -> McpClient.connect(server.config(), new AiCancellation(), Duration.ofSeconds(2)))
						.isInstanceOf(IOException.class);
				assertThat(server.posts).hasValue(0);
			}
		}
	}

	@Test
	void aStreamWithoutAnEndpointHasABoundedDeadline() throws Exception {
		try (McpSseFixture server = new McpSseFixture(0)) {
			server.mode = "no-endpoint";
			assertThatThrownBy(() -> McpClient.connect(server.config(), new AiCancellation(), Duration.ofSeconds(1)))
					.isInstanceOf(IOException.class).hasMessageContaining("timed out");
			assertThat(server.posts).hasValue(0);
		}
	}

	@Test
	void cancelsAReadAndClosesThePersistentStream() throws Exception {
		try (McpSseFixture server = new McpSseFixture(0);
				McpClient client = McpClient.connect(server.config(), new AiCancellation())) {
			server.mode = "wait";
			AiCancellation token = new AiCancellation();
			CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
				try {
					client.call(BurpBridge.HISTORY, new JsonObject(), token);
					return false;
				} catch (InterruptedException expected) {
					return true;
				} catch (IOException e) {
					return false;
				}
			});
			assertThat(server.called.await(3, TimeUnit.SECONDS)).isTrue();
			token.cancel();
			assertThat(future.get(3, TimeUnit.SECONDS)).isTrue();
			assertThat(client.isClosed()).isTrue();
		}
	}

	@Test
	void rejectsMalformedAndOversizedEventsWithoutEchoingTheirContents() throws Exception {
		for (String mode : List.of("malformed", "oversize")) {
			try (McpSseFixture server = new McpSseFixture(0);
					McpClient client = McpClient.connect(server.config(), new AiCancellation())) {
				server.mode = mode;
				assertThatThrownBy(() -> client.call(BurpBridge.HISTORY, new JsonObject(), new AiCancellation()))
						.isInstanceOf(IOException.class).hasMessageNotContaining("PRIVATE");
				assertThat(client.isClosed()).isTrue();
			}
		}
	}
}
