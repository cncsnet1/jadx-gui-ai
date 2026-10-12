package jadx.gui.ai;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class OpenAiClient implements AiClient {
	private static final int MAX_RESPONSE_CHARS = 1_000_000;
	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "jadx-ai-timeouts");
		thread.setDaemon(true);
		return thread;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

	private final URI endpoint;
	private final String apiKey;
	private final Duration timeout;
	private final Duration idleTimeout;

	public OpenAiClient(String baseUrl, String apiKey) {
		this(baseUrl, apiKey, Duration.ofMinutes(3), Duration.ofSeconds(45));
	}

	public OpenAiClient(String baseUrl, String apiKey, Duration timeout, Duration idleTimeout) {
		this.endpoint = endpoint(baseUrl);
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		if (this.apiKey.chars().anyMatch(ch -> ch < 32 || ch > 126)) {
			throw new IllegalArgumentException("API key contains invalid characters");
		}
		this.timeout = timeout;
		this.idleTimeout = idleTimeout;
		if (timeout.isNegative() || timeout.isZero() || idleTimeout.isNegative() || idleTimeout.isZero()) {
			throw new IllegalArgumentException("Timeout must be positive");
		}
	}

	public static URI endpoint(String address) {
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("API URL is required");
		}
		String url = address.trim().replaceAll("/+$", "");
		URI uri = URI.create(url);
		if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
				|| uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("Use an HTTP(S) API base URL without credentials, query or fragment");
		}
		return url.endsWith("/chat/completions") ? uri : URI.create(url + "/chat/completions");
	}

	@Override
	public String stream(String model, List<Map<String, String>> messages, double temperature, int maxTokens,
			AiCancellation cancellation, Consumer<String> onContent) throws IOException, InterruptedException {
		cancellation.check();
		if (model == null || model.isBlank() || maxTokens <= 0) {
			throw new IllegalArgumentException("Model and positive output token limit are required");
		}
		JsonObject body = new JsonObject();
		body.addProperty("model", model.trim());
		body.addProperty("temperature", temperature);
		body.addProperty("max_tokens", maxTokens);
		body.addProperty("stream", true);
		JsonArray items = new JsonArray();
		for (Map<String, String> message : messages) {
			JsonObject item = new JsonObject();
			item.addProperty("role", message.get("role"));
			item.addProperty("content", message.get("content"));
			items.add(item);
		}
		body.add("messages", items);
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
				.header("Content-Type", "application/json").header("Accept", "text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
		if (!apiKey.isEmpty()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}
		return exchange(builder.build(), cancellation, (input, lastRead) -> readStream(input, lastRead, cancellation, onContent));
	}

	/** Non-streaming function calls for the optional MCP loop. Ordinary analysis continues to stream. */
	public JsonObject completeWithTools(String model, JsonArray messages, JsonArray tools, int maxTokens,
			AiCancellation cancellation) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("temperature", 0.3);
		body.addProperty("max_tokens", maxTokens);
		body.addProperty("stream", false);
		body.add("messages", messages);
		body.add("tools", tools);
		body.addProperty("tool_choice", "auto");
		body.addProperty("parallel_tool_calls", false);
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
				.header("Content-Type", "application/json").header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
		if (!apiKey.isEmpty()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}
		return exchange(builder.build(), cancellation, (input, lastRead) -> {
			StringBuilder json = new StringBuilder();
			try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				char[] buffer = new char[4096];
				int count;
				while ((count = reader.read(buffer)) != -1) {
					cancellation.check();
					lastRead.set(System.nanoTime());
					json.append(buffer, 0, count);
					if (json.length() > MAX_RESPONSE_CHARS) {
						throw new IOException("AI tool response exceeds size limit");
					}
				}
			}
			try {
				JsonObject result = JsonParser.parseString(json.toString()).getAsJsonObject();
				JsonObject choice = result.getAsJsonArray("choices").get(0).getAsJsonObject();
				String reason = choice.get("finish_reason").getAsString();
				if (!"stop".equals(reason) && !"tool_calls".equals(reason)) {
					throw new IOException("AI tool response incomplete");
				}
				return choice.getAsJsonObject("message");
			} catch (RuntimeException e) {
				throw new IOException("Invalid AI tool response", e);
			}
		});
	}

	/** Read a bounded model catalog; some compatible providers only support manual model IDs. */
	public List<String> models(AiCancellation cancellation) throws IOException, InterruptedException {
		String address = endpoint.toString();
		URI models = URI.create(address.substring(0, address.length() - "/chat/completions".length()) + "/models");
		HttpRequest.Builder builder = HttpRequest.newBuilder(models).timeout(timeout).header("Accept", "application/json").GET();
		if (!apiKey.isEmpty()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}
		return exchange(builder.build(), cancellation, (input, lastRead) -> {
			StringBuilder json = new StringBuilder();
			try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				char[] buffer = new char[2048];
				int count;
				while ((count = reader.read(buffer)) != -1) {
					cancellation.check();
					lastRead.set(System.nanoTime());
					json.append(buffer, 0, count);
					if (json.length() > MAX_RESPONSE_CHARS) {
						throw new IOException("AI model catalog is too large");
					}
				}
			}
			try {
				JsonArray data = JsonParser.parseString(json.toString()).getAsJsonObject().getAsJsonArray("data");
				if (data == null) {
					throw new IOException("AI provider returned no model catalog");
				}
				Set<String> ids = new TreeSet<>();
				for (JsonElement item : data) {
					JsonElement id = item.getAsJsonObject().get("id");
					if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString() && !id.getAsString().isBlank()) {
						ids.add(id.getAsString());
					}
					if (ids.size() > 2000) {
						throw new IOException("AI model catalog is too large");
					}
				}
				return new ArrayList<>(ids);
			} catch (RuntimeException e) {
				throw new IOException("AI provider returned an invalid model catalog", e);
			}
		});
	}

	@FunctionalInterface
	private interface BodyReader<T> {
		T read(InputStream input, AtomicLong lastRead) throws IOException, InterruptedException;
	}

	public static final class HttpFailure extends IOException {
		private final int status;

		HttpFailure(int status) {
			super("AI HTTP " + status);
			this.status = status;
		}

		public int getStatus() {
			return status;
		}
	}

	private <T> T exchange(HttpRequest request, AiCancellation cancellation, BodyReader<T> reader)
			throws IOException, InterruptedException {
		cancellation.check();
		AtomicReference<InputStream> activeBody = new AtomicReference<>();
		AtomicReference<String> timeoutError = new AtomicReference<>();
		AtomicLong lastRead = new AtomicLong(System.nanoTime());
		long started = System.nanoTime();
		CompletableFuture<HttpResponse<InputStream>> response = HTTP.sendAsync(request, info -> {
			HttpResponse.BodySubscriber<InputStream> subscriber = HttpResponse.BodySubscribers.ofInputStream();
			// Own the body even if the response future was cancelled before headers arrived.
			subscriber.getBody().thenAccept(input -> {
				activeBody.set(input);
				if (cancellation.isCancelled() || timeoutError.get() != null) {
					closeQuietly(activeBody.getAndSet(null));
				}
			});
			return subscriber;
		});
		Runnable abort = () -> {
			response.cancel(true);
			closeQuietly(activeBody.getAndSet(null));
		};
		cancellation.onCancel(abort);
		ScheduledFuture<?> watchdog = TIMER.scheduleAtFixedRate(() -> {
			long now = System.nanoTime();
			if (now - started > timeout.toNanos() || now - lastRead.get() > idleTimeout.toNanos()) {
				timeoutError.compareAndSet(null, "AI request timed out");
				abort.run();
			}
		}, 100, 100, TimeUnit.MILLISECONDS);
		try {
			HttpResponse<InputStream> result = response.get();
			try (InputStream input = result.body()) {
				cancellation.check();
				if (result.statusCode() < 200 || result.statusCode() >= 300) {
					// Do not echo the provider's response: it may contain source code or credentials.
					throw new HttpFailure(result.statusCode());
				}
				T value = reader.read(input, lastRead);
				cancellation.check();
				if (timeoutError.get() != null) {
					throw new IOException(timeoutError.get());
				}
				return value;
			}
		} catch (CancellationException | ExecutionException | IOException e) {
			cancellation.check();
			if (timeoutError.get() != null) {
				throw new IOException(timeoutError.get(), e);
			}
			throw e instanceof IOException ? (IOException) e : new IOException("AI connection failed", e);
		} finally {
			watchdog.cancel(false);
			cancellation.removeListener(abort);
			abort.run();
		}
	}

	private static String readStream(InputStream input, AtomicLong lastRead, AiCancellation cancellation, Consumer<String> onContent)
			throws IOException, InterruptedException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			StringBuilder output = new StringBuilder();
			StringBuilder event = new StringBuilder();
			String line;
			boolean finished = false;
			while ((line = readLine(reader, lastRead)) != null) {
				lastRead.set(System.nanoTime());
				cancellation.check();
				if (line.isEmpty()) {
					if (event.length() > 0) {
						finished = consume(event.toString(), output, onContent);
						event.setLength(0);
						if (finished) {
							break;
						}
					}
				} else if (line.startsWith("data:")) {
					if (event.length() > 0) {
						event.append('\n');
					}
					event.append(line.substring(5).stripLeading());
					if (event.length() > MAX_RESPONSE_CHARS) {
						throw new IOException("AI stream event is too large");
					}
				}
			}
			if (!finished && event.length() > 0) {
				finished = consume(event.toString(), output, onContent);
			}
			cancellation.check();
			if (!finished) {
				throw new IOException("AI stream ended before completion; the result is incomplete");
			}
			if (output.length() == 0) {
				throw new IOException("AI returned no text");
			}
			return output.toString();
		}
	}

	private static String readLine(BufferedReader reader, AtomicLong lastRead) throws IOException {
		StringBuilder line = new StringBuilder();
		int ch;
		while ((ch = reader.read()) != -1) {
			if ((line.length() & 1023) == 0) {
				lastRead.set(System.nanoTime());
			}
			if (ch == '\n') {
				return line.toString();
			}
			if (ch == '\r') {
				reader.mark(1);
				if (reader.read() != '\n') {
					reader.reset();
				}
				return line.toString();
			}
			line.append((char) ch);
			if (line.length() > MAX_RESPONSE_CHARS) {
				throw new IOException("AI stream line is too large");
			}
		}
		return line.length() == 0 ? null : line.toString();
	}

	private static boolean consume(String event, StringBuilder output, Consumer<String> onContent) throws IOException {
		if ("[DONE]".equals(event.trim())) {
			return true;
		}
		try {
			JsonObject json = JsonParser.parseString(event).getAsJsonObject();
			if (json.has("error")) {
				throw new IOException("AI provider returned an error");
			}
			JsonArray choices = json.getAsJsonArray("choices");
			if (choices == null || choices.size() == 0) {
				return false; // usage-only event
			}
			JsonObject choice = choices.get(0).getAsJsonObject();
			JsonObject delta = choice.getAsJsonObject("delta");
			JsonElement content = delta == null ? null : delta.get("content");
			if (content != null && !content.isJsonNull()) {
				String chunk = content.getAsString();
				if (output.length() + chunk.length() > MAX_RESPONSE_CHARS) {
					throw new IOException("AI response is too large");
				}
				output.append(chunk);
				onContent.accept(chunk);
			}
			JsonElement reason = choice.get("finish_reason");
			if (reason != null && !reason.isJsonNull()) {
				if (!"stop".equals(reason.getAsString())) {
					throw new IOException("AI response incomplete (" + reason.getAsString() + ")");
				}
				return true;
			}
			return false;
		} catch (RuntimeException e) {
			throw new IOException("Invalid AI stream response", e);
		}
	}

	private static void closeQuietly(InputStream stream) {
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException ignored) {
				// Cancellation/cleanup must not hide the original error.
			}
		}
	}
}
