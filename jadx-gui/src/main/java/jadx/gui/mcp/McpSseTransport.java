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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.gui.ai.AiCancellation;

/** Legacy HTTP+SSE transport, including the direct endpoint exposed by Burp's MCP extension. */
final class McpSseTransport implements McpTransport {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	private final URI endpoint;
	private final String bearer;
	private final Duration timeout;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicReference<InputStream> stream = new AtomicReference<>();
	private final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
	private final CompletableFuture<URI> postEndpoint = new CompletableFuture<>();
	private volatile CompletableFuture<JsonObject> pending;
	private volatile JsonElement pendingId;
	private boolean started;

	McpSseTransport(McpConnection config, Duration timeout) throws IOException {
		endpoint = URI.create(config.url);
		this.timeout = timeout;
		bearer = config.bearerEnvironment.isEmpty() ? "" : System.getenv(config.bearerEnvironment);
		if (bearer == null || bearer.chars().anyMatch(ch -> ch < 32 || ch > 126)) {
			throw new IOException("Missing or invalid bearer token environment variable");
		}
	}

	@Override
	public JsonObject exchange(JsonObject message, AiCancellation cancellation) throws IOException, InterruptedException {
		cancellation.check();
		if (closed.get()) {
			throw new IOException("MCP SSE connection closed");
		}
		long deadline = System.nanoTime() + (started
				? timeout.toNanos()
				: Math.min(timeout.toNanos(), TimeUnit.SECONDS.toNanos(10)));
		Runnable abort = this::close;
		cancellation.onCancel(abort);
		try {
			if (!started) {
				started = true;
				CompletableFuture<HttpResponse<InputStream>> opening = HTTP.sendAsync(
						request(endpoint).header("Accept", "text/event-stream").GET().build(), info -> {
							HttpResponse.BodySubscriber<InputStream> subscriber = HttpResponse.BodySubscribers.ofInputStream();
							subscriber.getBody().thenAccept(input -> {
								stream.set(input);
								if (closed.get()) {
									closeStream();
								}
							});
							return subscriber;
						});
				active.set(opening);
				HttpResponse<InputStream> response = await(opening, deadline, cancellation);
				if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
						.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
					throw new IOException("MCP SSE endpoint did not return an event stream (HTTP " + response.statusCode() + ")");
				}
				Thread reader = new Thread(() -> readStream(response.body()), "jadx-mcp-sse");
				reader.setDaemon(true);
				reader.start();
			}
			URI target = await(postEndpoint, deadline, cancellation);
			CompletableFuture<JsonObject> reply = new CompletableFuture<>();
			pendingId = message.get("id");
			pending = reply;
			CompletableFuture<HttpResponse<Void>> posting = HTTP.sendAsync(request(target).timeout(timeout)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(message.toString(), StandardCharsets.UTF_8)).build(),
					HttpResponse.BodyHandlers.discarding());
			active.set(posting);
			HttpResponse<Void> response = await(posting, deadline, cancellation);
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("MCP SSE message rejected (HTTP " + response.statusCode() + ")");
			}
			return message.has("id") ? await(reply, deadline, cancellation) : new JsonObject();
		} catch (IOException | InterruptedException e) {
			close();
			cancellation.check();
			throw e;
		} finally {
			cancellation.removeListener(abort);
			pending = null;
			pendingId = null;
			CompletableFuture<?> request = active.getAndSet(null);
			if (request != null && !request.isDone()) {
				request.cancel(true);
			}
		}
	}

	private HttpRequest.Builder request(URI uri) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri);
		if (!bearer.isEmpty()) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return request;
	}

	private <T> T await(CompletableFuture<T> future, long deadline, AiCancellation cancellation)
			throws IOException, InterruptedException {
		while (true) {
			cancellation.check();
			if (closed.get()) {
				throw new IOException("MCP SSE connection closed");
			}
			long left = deadline - System.nanoTime();
			if (left <= 0) {
				throw new IOException("MCP SSE request timed out");
			}
			try {
				return future.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
			} catch (TimeoutException ignored) {
				// Poll the shared cancellation token while the stream remains open.
			} catch (ExecutionException | java.util.concurrent.CancellationException e) {
				throw new IOException("MCP SSE connection failed", e);
			}
		}
	}

	private void readStream(InputStream input) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String event = "message";
			StringBuilder data = new StringBuilder();
			String line;
			int size = 0;
			while (!closed.get() && (line = McpJson.line(reader)) != null) {
				if (line.endsWith("\r")) {
					line = line.substring(0, line.length() - 1);
				}
				size += line.length();
				if (size > McpJson.MAX_CHARS) {
					throw new IOException("MCP SSE event exceeds size limit");
				}
				if (line.isEmpty()) {
					if (data.length() > 0) {
						dispatch(event, data.toString());
					}
					event = "message";
					data.setLength(0);
					size = 0;
				} else if (line.startsWith("event:")) {
					event = line.substring(6).stripLeading();
				} else if (line.startsWith("data:")) {
					data.append(line.substring(5).stripLeading()).append('\n');
				}
			}
		} catch (IOException | RuntimeException e) {
			// Do not print server-supplied text or session URLs into logs.
		} finally {
			close();
		}
	}

	private void dispatch(String event, String text) throws IOException {
		if ("endpoint".equals(event)) {
			URI target = endpoint.resolve(text.trim());
			if (!endpoint.getScheme().equals(target.getScheme()) || target.getHost() == null
					|| !endpoint.getHost().equalsIgnoreCase(target.getHost()) || port(endpoint) != port(target)
					|| target.getUserInfo() != null || target.getFragment() != null || postEndpoint.isDone()) {
				throw new IOException("MCP SSE advertised an invalid message endpoint");
			}
			postEndpoint.complete(target);
		} else if ("message".equals(event)) {
			JsonObject message = McpJson.object(text);
			if (message.has("method")) {
				if (message.has("id")) {
					throw new IOException("Server requested an unsupported client capability");
				}
				return;
			}
			CompletableFuture<JsonObject> response = pending;
			if (response == null || pendingId == null || !pendingId.equals(message.get("id")) || !response.complete(message)) {
				throw new IOException("Unexpected MCP SSE response");
			}
		}
	}

	private static int port(URI uri) {
		return uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
	}

	@Override
	public boolean isClosed() {
		return closed.get();
	}

	@Override
	public void close() {
		closed.set(true);
		CompletableFuture<?> operation = active.getAndSet(null);
		if (operation != null) {
			operation.cancel(true);
		}
		closeStream();
	}

	private void closeStream() {
		InputStream input = stream.getAndSet(null);
		if (input != null) {
			try {
				input.close();
			} catch (IOException ignored) {
				// Closing is best effort.
			}
		}
	}
}
