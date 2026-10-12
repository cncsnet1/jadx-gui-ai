package jadx.gui.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;

import jadx.gui.ai.AiCancellation;

final class McpHttpTransport implements McpTransport {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "jadx-mcp-timeouts");
		thread.setDaemon(true);
		return thread;
	});
	private final URI endpoint;
	private final String bearer;
	private final Duration timeout;
	private final AtomicReference<Runnable> active = new AtomicReference<>();
	private volatile boolean closed;
	private String version;
	private String session;

	McpHttpTransport(McpConnection config, Duration timeout) throws IOException {
		endpoint = URI.create(config.url);
		this.timeout = timeout;
		bearer = config.bearerEnvironment.isEmpty() ? "" : System.getenv(config.bearerEnvironment);
		if (bearer == null || bearer.chars().anyMatch(ch -> ch < 32 || ch > 126)) {
			throw new IOException("Missing or invalid bearer token environment variable");
		}
	}

	@Override
	public void version(String value) {
		version = value;
	}

	@Override
	public JsonObject exchange(JsonObject message, AiCancellation cancellation) throws IOException, InterruptedException {
		cancellation.check();
		if (closed) {
			throw new IOException("MCP connection closed");
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
				.header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(message.toString(), StandardCharsets.UTF_8));
		if (!bearer.isEmpty()) {
			builder.header("Authorization", "Bearer " + bearer);
		}
		if (version != null) {
			builder.header("MCP-Protocol-Version", version);
		}
		if (session != null) {
			builder.header("Mcp-Session-Id", session);
		}
		AtomicReference<InputStream> body = new AtomicReference<>();
		AtomicBoolean aborted = new AtomicBoolean();
		CompletableFuture<HttpResponse<InputStream>> response = HTTP.sendAsync(builder.build(), info -> {
			HttpResponse.BodySubscriber<InputStream> subscriber = HttpResponse.BodySubscribers.ofInputStream();
			subscriber.getBody().thenAccept(input -> {
				body.set(input);
				if (aborted.get()) {
					closeBody(body);
				}
			});
			return subscriber;
		});
		Runnable abort = () -> {
			aborted.set(true);
			response.cancel(true);
			closeBody(body);
		};
		active.set(abort);
		if (closed) {
			abort.run();
		}
		cancellation.onCancel(abort);
		ScheduledFuture<?> deadline = TIMER.schedule(abort, timeout.toMillis(), TimeUnit.MILLISECONDS);
		try {
			HttpResponse<InputStream> result = response.get();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(result.body(), StandardCharsets.UTF_8))) {
				if (result.statusCode() < 200 || result.statusCode() >= 300) {
					throw new IOException("MCP HTTP " + result.statusCode() + "; reconnect if the session expired");
				}
				if ("initialize".equals(McpJson.string(message, "method", ""))) {
					session = result.headers().firstValue("Mcp-Session-Id").orElse(null);
					if (session != null && (session.length() > 1024 || session.chars().anyMatch(ch -> ch < 33 || ch > 126))) {
						throw new IOException("Invalid MCP session identifier");
					}
				}
				if (!message.has("id")) {
					if (result.statusCode() != 202) {
						throw new IOException("MCP notification was not accepted");
					}
					return new JsonObject();
				}
				String type = result.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
				JsonObject value;
				if (type.startsWith("text/event-stream")) {
					value = readEvents(reader, message, cancellation);
				} else if (type.startsWith("application/json")) {
					value = McpJson.object(McpJson.read(reader));
				} else {
					throw new IOException("Unsupported MCP response content type");
				}
				cancellation.check();
				if (aborted.get()) {
					throw new IOException("MCP request timed out or disconnected");
				}
				return value;
			}
		} catch (ExecutionException | CancellationException e) {
			cancellation.check();
			throw new IOException("MCP connection failed, timed out or disconnected", e);
		} catch (IOException e) {
			cancellation.check();
			throw e;
		} finally {
			deadline.cancel(false);
			cancellation.removeListener(abort);
			active.compareAndSet(abort, null);
			abort.run();
		}
	}

	private static JsonObject readEvents(BufferedReader reader, JsonObject request, AiCancellation cancellation)
			throws IOException, InterruptedException {
		StringBuilder data = new StringBuilder();
		String line;
		int total = 0;
		while ((line = McpJson.line(reader)) != null) {
			cancellation.check();
			total += line.length();
			if (total > McpJson.MAX_CHARS) {
				throw new IOException("MCP response exceeds size limit");
			}
			if (line.endsWith("\r")) {
				line = line.substring(0, line.length() - 1);
			}
			if (line.isEmpty() && data.length() > 0) {
				JsonObject event = McpJson.object(data.toString());
				data.setLength(0);
				if (event.has("method") && event.has("id")) {
					throw new IOException("Server requested an unsupported client capability");
				}
				if (request.get("id").equals(event.get("id")) && !event.has("method")) {
					return event;
				}
			} else if (line.startsWith("data:")) {
				data.append(line.substring(5).stripLeading()).append('\n');
			}
		}
		throw new IOException("MCP event stream ended without a response");
	}

	private static void closeBody(AtomicReference<InputStream> body) {
		InputStream input = body.getAndSet(null);
		if (input != null) {
			try {
				input.close();
			} catch (IOException ignored) {
				// Cancellation must not hide the original error.
			}
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		closed = true;
		Runnable abort = active.getAndSet(null);
		if (abort != null) {
			abort.run();
		}
	}
}
