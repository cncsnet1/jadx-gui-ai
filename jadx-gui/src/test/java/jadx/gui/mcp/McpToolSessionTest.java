package jadx.gui.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.OpenAiClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolSessionTest {

	@Test
	void completesModelToolRoundTripOnlyAfterApproval() throws Exception {
		try (ModelFixture model = new ModelFixture("mcp_1", false);
				McpClient client = McpClient.connect(McpClientTest.fixture("normal"), new AiCancellation())) {
			AtomicInteger approvals = new AtomicInteger();
			McpToolSession session = new McpToolSession(List.of(client), (connection, name, arguments, token) -> {
				assertThat(name).isEqualTo("echo");
				assertThat(arguments.get("query").getAsString()).isEqualTo("fixture");
				approvals.incrementAndGet();
				return true;
			});
			String result = run(session, model);
			assertThat(result).contains("Final analysis", "MCP: echo");
			assertThat(approvals).hasValue(1);
			assertThat(model.toolMessage.get()).contains("fixture-result");
			assertThat(model.requests).hasValue(2);
		}
	}

	@Test
	void deniedCallNeverExecutesTheToolAndIsReportedToModel() throws Exception {
		try (ModelFixture model = new ModelFixture("mcp_1", false);
				McpClient client = McpClient.connect(McpClientTest.fixture("exit"), new AiCancellation())) {
			McpToolSession session = new McpToolSession(List.of(client), (connection, name, arguments, token) -> false);
			assertThat(run(session, model)).contains("denied");
			assertThat(model.toolMessage.get()).contains("User denied");
			assertThat(client.isClosed()).isFalse();
		}
	}

	@Test
	void unknownToolFailsBeforeAskingForApproval() throws Exception {
		try (ModelFixture model = new ModelFixture("invented", false);
				McpClient client = McpClient.connect(McpClientTest.fixture("normal"), new AiCancellation())) {
			AtomicInteger approvals = new AtomicInteger();
			McpToolSession session = new McpToolSession(List.of(client), (connection, name, arguments, token) -> {
				approvals.incrementAndGet();
				return true;
			});
			assertThatThrownBy(() -> run(session, model)).isInstanceOf(IOException.class).hasMessageContaining("unknown");
			assertThat(approvals).hasValue(0);
		}
	}

	@Test
	void capsRepeatedCallsAndChecksCancellationAfterApproval() throws Exception {
		try (ModelFixture model = new ModelFixture("mcp_1", true);
				McpClient client = McpClient.connect(McpClientTest.fixture("normal"), new AiCancellation())) {
			AtomicInteger approvals = new AtomicInteger();
			McpToolSession session = new McpToolSession(List.of(client), (connection, name, arguments, token) -> {
				approvals.incrementAndGet();
				return true;
			});
			assertThatThrownBy(() -> run(session, model)).isInstanceOf(IOException.class).hasMessageContaining("budget");
			assertThat(approvals).hasValue(4);
			McpToolSession cancelled = new McpToolSession(List.of(client), (connection, name, arguments, token) -> {
				token.cancel();
				return true;
			});
			assertThatThrownBy(() -> run(cancelled, model)).isInstanceOf(InterruptedException.class);
		}
	}

	private static String run(McpToolSession session, ModelFixture model) throws Exception {
		return session.run(model.client(), "test-model", List.of(Map.of("role", "user", "content", "explain fixture")),
				1024, new AiCancellation(), ignored -> { });
	}

	private static final class ModelFixture implements AutoCloseable {
		private final HttpServer server;
		private final AtomicInteger requests = new AtomicInteger();
		private final AtomicReference<String> toolMessage = new AtomicReference<>();

		private ModelFixture(String toolName, boolean repeat) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/v1/chat/completions", exchange -> {
				JsonObject request = McpJson.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				JsonArray messages = request.getAsJsonArray("messages");
				JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
				if (last.get("role").getAsString().equals("tool")) {
					toolMessage.set(last.get("content").getAsString());
				}
				int number = requests.incrementAndGet();
				JsonObject response = new JsonObject();
				response.addProperty("role", "assistant");
				JsonObject choice = new JsonObject();
				if (number == 1 || repeat) {
					JsonObject function = new JsonObject();
					function.addProperty("name", toolName);
					function.addProperty("arguments", "{\"query\":\"fixture\"}");
					JsonObject call = new JsonObject();
					call.addProperty("id", "call_" + number);
					call.addProperty("type", "function");
					call.add("function", function);
					JsonArray calls = new JsonArray();
					calls.add(call);
					response.add("tool_calls", calls);
					choice.addProperty("finish_reason", "tool_calls");
				} else {
					response.addProperty("content", "Final analysis");
					choice.addProperty("finish_reason", "stop");
				}
				choice.add("message", response);
				JsonArray choices = new JsonArray();
				choices.add(choice);
				JsonObject result = new JsonObject();
				result.add("choices", choices);
				byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
			server.start();
		}

		private OpenAiClient client() {
			return new OpenAiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "",
					Duration.ofSeconds(10), Duration.ofSeconds(5));
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
