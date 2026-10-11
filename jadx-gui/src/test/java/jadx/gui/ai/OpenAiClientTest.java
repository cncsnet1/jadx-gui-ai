package jadx.gui.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class OpenAiClientTest {
	private HttpServer server;
	private ExecutorService workers;
	private String url;
	private final AtomicReference<String> request = new AtomicReference<>();
	private final AtomicReference<String> authorization = new AtomicReference<>();
	private final CountDownLatch release = new CountDownLatch(1);

	@BeforeEach
	void setup() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		workers = Executors.newCachedThreadPool();
		server.setExecutor(workers);
		server.start();
		url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
	}

	@AfterEach
	void cleanup() {
		release.countDown();
		server.stop(0);
		workers.shutdownNow();
	}

	@Test
	void streamsFragmentedUnicodeAndNullableDeltas() throws Exception {
		String stream = ": keepalive\r\nevent: message\r\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\r\n\r\n"
				+ event("源码😀") + "data: {\"choices\":[],\"usage\":{}}\n\n" + event(" OK") + "data: [DONE]\n\n";
		respond(200, stream, true);
		StringBuilder received = new StringBuilder();
		assertThat(client().stream("test-model", messages(), 0.3, 512, new AiCancellation(), received::append)).isEqualTo("源码😀 OK");
		assertThat(received.toString()).isEqualTo("源码😀 OK");
		JsonObject body = JsonParser.parseString(request.get()).getAsJsonObject();
		assertThat(body.get("stream").getAsBoolean()).isTrue();
		assertThat(body.has("top_k")).isFalse();
		assertThat(body.get("max_tokens").getAsInt()).isEqualTo(512);
		assertThat(authorization.get()).isNull();
	}

	@Test
	void acceptsFinishReasonWithoutDoneAndFullEndpoint() throws Exception {
		respond(200, event("OK") + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n", false);
		OpenAiClient client = new OpenAiClient(url + "/chat/completions/", "secret");
		assertThat(client.stream("test", messages(), 0, 512, new AiCancellation(), s -> {
		})).isEqualTo("OK");
		assertThat(authorization.get()).isEqualTo("Bearer secret");
	}

	@ParameterizedTest
	@ValueSource(
			strings = { "data: not-json\n\n", "data: {\"error\":{\"message\":\"private source\"}}\n\n",
					"data: [DONE]\n\n", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
					"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\n" }
	)
	void rejectsMalformedEmptyAndIncompleteResults(String response) {
		respond(200, response, false);
		assertThatThrownBy(() -> run(client(), new AiCancellation())).isInstanceOf(IOException.class)
				.hasMessageNotContaining("private source");
	}

	@Test
	void rejectsOversizedLine() {
		respond(200, "data: " + "x".repeat(1_000_001), false);
		assertThatThrownBy(() -> run(client(), new AiCancellation())).isInstanceOf(IOException.class).hasMessageContaining("too large");
	}

	@Test
	void reportsStatusWithoutLeakingResponseBody() {
		respond(401, "secret credentials and source", false);
		assertThatThrownBy(() -> run(client(), new AiCancellation())).isInstanceOf(IOException.class).hasMessage("AI HTTP 401");
	}

	@Test
	void cancellationInterruptsBlockedBody() throws Exception {
		CountDownLatch started = stall(true);
		AiCancellation cancellation = new AiCancellation();
		CompletableFuture<Exception> failure = failureAsync(client(), cancellation);
		assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
		cancellation.cancel();
		assertThat(failure.get(3, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
	}

	@Test
	void cancellationInterruptsWaitingForHeaders() throws Exception {
		CountDownLatch started = stall(false);
		AiCancellation cancellation = new AiCancellation();
		CompletableFuture<Exception> failure = failureAsync(client(), cancellation);
		assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
		cancellation.cancel();
		assertThat(failure.get(3, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
	}

	@Test
	void idleTimeoutClosesBlockedStream() {
		stall(true);
		OpenAiClient client = new OpenAiClient(url, "", Duration.ofSeconds(5), Duration.ofMillis(200));
		assertThatThrownBy(() -> run(client, new AiCancellation())).isInstanceOf(IOException.class).hasMessageContaining("timed out");
	}

	@Test
	void alreadyCancelledDoesNotSendRequest() {
		AiCancellation cancellation = new AiCancellation();
		cancellation.cancel();
		assertThatThrownBy(() -> run(client(), cancellation)).isInstanceOf(InterruptedException.class);
		assertThat(request.get()).isNull();
	}

	@Test
	void validatesEndpoint() {
		assertThat(OpenAiClient.endpoint(" https://example.com/v1/ ").toString()).isEqualTo("https://example.com/v1/chat/completions");
		for (String invalid : List.of("", "file:///local", "https://user:secret@example.com/v1", "https://example.com?key=secret")) {
			assertThatThrownBy(() -> OpenAiClient.endpoint(invalid)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	private CountDownLatch stall(boolean sendHeaders) {
		CountDownLatch started = new CountDownLatch(1);
		server.createContext("/v1/chat/completions", exchange -> {
			try {
				if (sendHeaders) {
					exchange.sendResponseHeaders(200, 0);
					exchange.getResponseBody().write(event("partial").getBytes(StandardCharsets.UTF_8));
					exchange.getResponseBody().flush();
				}
				started.countDown();
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		return started;
	}

	@Test
	void modelCatalogUsesSameBaseAndAuthenticationWithoutSendingSource() throws Exception {
		AtomicReference<String> method = new AtomicReference<>();
		server.createContext("/v1/models", exchange -> {
			try {
				method.set(exchange.getRequestMethod());
				request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] body = "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"z-model\"},{\"id\":null}]}"
						.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			} finally {
				exchange.close();
			}
		});
		assertThat(new OpenAiClient(url + "/chat/completions", "catalog-key").models(new AiCancellation()))
				.containsExactly("a-model", "z-model");
		assertThat(method.get()).isEqualTo("GET");
		assertThat(request.get()).isEmpty();
		assertThat(authorization.get()).isEqualTo("Bearer catalog-key");
	}

	@ParameterizedTest
	@ValueSource(strings = { "not-json", "{}", "{\"data\":[null]}" })
	void malformedModelCatalogIsNotAccepted(String response) {
		catalog(200, response);
		assertThatThrownBy(() -> client().models(new AiCancellation())).isInstanceOf(IOException.class);
	}

	@Test
	void modelDiscoveryReportsTypedErrorsWithoutProviderBody() {
		catalog(401, "secret in error body");
		assertThatThrownBy(() -> client().models(new AiCancellation())).isInstanceOf(OpenAiClient.HttpFailure.class)
				.hasMessage("AI HTTP 401").hasMessageNotContaining("secret");
	}

	@Test
	void modelDiscoveryCancellationClosesAStalledBody() throws Exception {
		CountDownLatch received = new CountDownLatch(1);
		server.createContext("/v1/models", exchange -> {
			try {
				exchange.sendResponseHeaders(200, 0);
				exchange.getResponseBody().write('{');
				exchange.getResponseBody().flush();
				received.countDown();
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		AiCancellation cancellation = new AiCancellation();
		CompletableFuture<Exception> failure = CompletableFuture.supplyAsync(() -> {
			try {
				client().models(cancellation);
				return null;
			} catch (Exception e) {
				return e;
			}
		});
		assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
		cancellation.cancel();
		assertThat(failure.get(3, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
	}

	@Test
	void modelCatalogIsBoundedAndBadCredentialErrorsDoNotEchoKeys() {
		catalog(200, " ".repeat(1_000_001));
		assertThatThrownBy(() -> client().models(new AiCancellation())).isInstanceOf(IOException.class).hasMessageContaining("too large");
		assertThatThrownBy(() -> new OpenAiClient(url, "secret\nheader")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining("secret");
	}

	private void catalog(int status, String response) {
		server.createContext("/v1/models", exchange -> {
			try {
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			} finally {
				exchange.close();
			}
		});
	}

	private void respond(int status, String body, boolean fragmented) {
		server.createContext("/v1/chat/completions", exchange -> {
			HttpExchange resource = exchange;
			try {
				request.set(new String(resource.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				authorization.set(resource.getRequestHeaders().getFirst("Authorization"));
				resource.getResponseHeaders().add("Content-Type", "text/event-stream");
				resource.sendResponseHeaders(status, 0);
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				if (fragmented) {
					for (byte b : bytes) {
						resource.getResponseBody().write(b);
						resource.getResponseBody().flush();
					}
				} else {
					resource.getResponseBody().write(bytes);
				}
			} finally {
				resource.close();
			}
		});
	}

	private CompletableFuture<Exception> failureAsync(OpenAiClient client, AiCancellation cancellation) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				run(client, cancellation);
				return null;
			} catch (Exception e) {
				return e;
			}
		}, workers);
	}

	private OpenAiClient client() {
		return new OpenAiClient(url, "", Duration.ofSeconds(5), Duration.ofSeconds(4));
	}

	private static String run(OpenAiClient client, AiCancellation token) throws Exception {
		return client.stream("test-model", messages(), 0.3, 512, token, chunk -> {
		});
	}

	private static List<Map<String, String>> messages() {
		return List.of(Map.of("role", "user", "content", "explain"));
	}

	private static String event(String content) {
		return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n\n";
	}
}
