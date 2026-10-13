package jadx.gui.mcp;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import jadx.gui.ai.AiCancellation;
import jadx.gui.settings.JadxSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

	static McpConnection fixture(String mode) throws Exception {
		McpConnection config = new McpConnection();
		config.command = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classes = Path.of(McpProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		String gson = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		config.args = List.of("-Dfile.encoding=UTF-8", "-cp", classes + File.pathSeparator + gson, McpProcessFixture.class.getName(), mode);
		return config;
	}

	@Test
	void launchesStdioNegotiatesAndCallsRealProcessWithPagination() throws Exception {
		try (McpClient client = McpClient.connect(fixture("pages"), new AiCancellation())) {
			assertThat(client.tools()).hasSize(2);
			assertThat(client.call("echo", McpJson.object("{\"message\":\"中文\"}"), new AiCancellation()).toString())
					.contains("fixture-result", "中文");
			assertThatThrownBy(() -> client.call("invented", new JsonObject(), new AiCancellation())).isInstanceOf(IOException.class);
		}
	}

	@Test
	void rejectsUnsupportedVersionAndRepeatedCursor() throws Exception {
		assertThatThrownBy(() -> McpClient.connect(fixture("version"), new AiCancellation()))
				.isInstanceOf(IOException.class).hasMessageContaining("version");
		assertThatThrownBy(() -> McpClient.connect(fixture("loop"), new AiCancellation()))
				.isInstanceOf(IOException.class).hasMessageContaining("pagination");
	}

	@Test
	void disconnectsOnTimeoutProcessExitAndOversizedStdout() throws Exception {
		// This deadline also includes starting the fixture JVM on a busy Windows runner.
		try (McpClient client = McpClient.connect(fixture("wait"), new AiCancellation(), Duration.ofSeconds(5))) {
			assertThatThrownBy(() -> client.call("echo", new JsonObject(), new AiCancellation()))
					.isInstanceOf(IOException.class).hasMessageContaining("timed out");
			assertThat(client.isClosed()).isTrue();
		}
		for (String mode : List.of("exit", "oversize")) {
			try (McpClient client = McpClient.connect(fixture(mode), new AiCancellation())) {
				assertThatThrownBy(() -> client.call("echo", new JsonObject(), new AiCancellation()))
						.isInstanceOf(IOException.class).hasMessageContaining("disconnected");
			}
		}
	}

	@Test
	void cancelsBlockedStdioRequest() throws Exception {
		try (McpClient client = McpClient.connect(fixture("wait"), new AiCancellation())) {
			AiCancellation token = new AiCancellation();
			CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
				try {
					client.call("echo", new JsonObject(), token);
					return false;
				} catch (InterruptedException e) {
					return true;
				} catch (IOException e) {
					return false;
				}
			});
			Thread.sleep(100);
			token.cancel();
			assertThat(result.get(3, TimeUnit.SECONDS)).isTrue();
			assertThat(client.isClosed()).isTrue();
		}
	}

	@Test
	void httpHandlesSseSessionHeadersAndNotifications() throws Exception {
		AtomicBoolean initialized = new AtomicBoolean();
		AtomicBoolean headers = new AtomicBoolean(true);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", exchange -> {
			JsonObject request = McpJson.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			String method = request.get("method").getAsString();
			JsonObject result = new JsonObject();
			if (method.equals("initialize")) {
				result.addProperty("protocolVersion", McpJson.VERSION);
				result.add("capabilities", McpJson.object("{\"tools\":{}}"));
				exchange.getResponseHeaders().set("Mcp-Session-Id", "fixture-session");
			} else {
				if (!"fixture-session".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))
						|| !McpJson.VERSION.equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
						|| !exchange.getRequestHeaders().getFirst("Accept").contains("text/event-stream")) {
					headers.set(false);
				}
				if (method.equals("notifications/initialized")) {
					initialized.set(true);
					exchange.sendResponseHeaders(202, -1);
					exchange.close();
					return;
				}
				if (method.equals("tools/list")) {
					result.add("tools", McpProjectTools.list());
				} else {
					result = McpJson.text("http-result", false);
				}
			}
			String response = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\r\n\r\n"
					+ "data: " + McpJson.result(request.get("id"), result) + "\r\n\r\n";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		try {
			McpConnection config = new McpConnection();
			config.transport = "http";
			config.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
			try (McpClient client = McpClient.connect(config, new AiCancellation())) {
				assertThat(client.tools()).hasSize(10);
				assertThat(client.call("get_project", new JsonObject(), new AiCancellation()).toString()).contains("http-result");
			}
			assertThat(initialized).isTrue();
			assertThat(headers).isTrue();
		} finally {
			server.stop(0);
		}
	}

	@Test
	void httpCancellationClosesAStalledResponseBody() throws Exception {
		CountDownLatch reading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write("data: ".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			reading.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.start();
		AiCancellation token = new AiCancellation();
		try {
			McpConnection config = new McpConnection();
			config.transport = "http";
			config.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
			CompletableFuture<Boolean> call = CompletableFuture.supplyAsync(() -> {
				try (McpClient ignored = McpClient.connect(config, token)) {
					return false;
				} catch (InterruptedException e) {
					return true;
				} catch (IOException e) {
					return false;
				}
			});
			assertThat(reading.await(3, TimeUnit.SECONDS)).isTrue();
			token.cancel();
			assertThat(call.get(3, TimeUnit.SECONDS)).isTrue();
		} finally {
			token.cancel();
			release.countDown();
			server.stop(0);
		}
	}

	@Test
	void validatesAddressesAndPersistsOnlyExplicitConfiguration() {
		McpConnection config = new McpConnection();
		config.transport = "http";
		for (String url : List.of("http://example.com/mcp", "https://user:pass@example.com", "https://example.com?token=secret")) {
			config.url = url;
			assertThatThrownBy(config::validate).isInstanceOf(IllegalArgumentException.class);
		}
		config.url = "https://example.com/mcp";
		config.bearerEnvironment = "MY_MCP_TOKEN";
		config.validate();
		JadxSettings settings = new JadxSettings();
		settings.setMcpConnections(List.of(config));
		config.name = "edited";
		assertThat(settings.getMcpConnections().get(0).name).isEqualTo("MCP");
		String encoded = new Gson().toJson(settings.getMcpConnections());
		assertThat(encoded).contains("MY_MCP_TOKEN").doesNotContain("enabled", "autoStart");
	}
}
