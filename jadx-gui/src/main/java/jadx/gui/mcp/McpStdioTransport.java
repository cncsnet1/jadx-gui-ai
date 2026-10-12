package jadx.gui.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.gui.ai.AiCancellation;

final class McpStdioTransport implements McpTransport {
	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "jadx-mcp-process-timeouts");
		thread.setDaemon(true);
		return thread;
	});

	private final Process process;
	private final BufferedWriter writer;
	private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Duration timeout;

	McpStdioTransport(McpConnection config, Duration timeout) throws IOException {
		this.timeout = timeout;
		List<String> command = new ArrayList<>();
		command.add(config.command);
		command.addAll(config.args);
		ProcessBuilder builder = new ProcessBuilder(command);
		// Explicit executable/argv; never interpret model output as a shell command.
		Map<String, String> environment = builder.environment();
		environment.clear();
		for (String key : List.of("PATH", "Path", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP",
				"HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "JAVA_HOME", "LANG")) {
			String value = System.getenv(key);
			if (value != null) {
				environment.put(key, value);
			}
		}
		for (Map.Entry<String, String> entry : config.environment.entrySet()) {
			String value = System.getenv(entry.getValue());
			if (value == null) {
				throw new IOException("Missing environment variable: " + entry.getValue());
			}
			environment.put(entry.getKey(), value);
		}
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		process = builder.start();
		writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		Thread reader = new Thread(this::read, "jadx-mcp-stdio");
		reader.setDaemon(true);
		reader.start();
	}

	private void read() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (!closed.get() && (line = McpJson.line(reader)) != null) {
				JsonObject message = McpJson.object(line);
				if (!"2.0".equals(McpJson.string(message, "jsonrpc", ""))) {
					throw new IOException("Invalid MCP envelope");
				}
				JsonElement id = message.get("id");
				if (message.has("method")) {
					if (id != null) {
						write("ping".equals(McpJson.string(message, "method", "")) ? McpJson.result(id, new JsonObject())
								: McpJson.error(id, -32601, "Client capability not supported"));
					}
				} else if (id != null) {
					CompletableFuture<JsonObject> future = pending.get(id.toString());
					if (future != null) {
						future.complete(message);
					}
				}
			}
		} catch (Exception e) {
			// Raw stderr, protocol data and credentials must not enter application logs.
		} finally {
			close();
		}
	}

	private synchronized void write(JsonObject message) throws IOException {
		if (closed.get()) {
			throw new IOException("MCP process disconnected; reconnect before retrying");
		}
		writer.write(message.toString());
		writer.newLine();
		writer.flush();
	}

	@Override
	public JsonObject exchange(JsonObject message, AiCancellation cancellation) throws IOException, InterruptedException {
		cancellation.check();
		JsonElement id = message.get("id");
		if (id == null) {
			write(message);
			return new JsonObject();
		}
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		String key = id.toString();
		pending.put(key, future);
		Runnable abort = this::close;
		cancellation.onCancel(abort);
		AtomicBoolean timedOut = new AtomicBoolean();
		ScheduledFuture<?> deadline = TIMER.schedule(() -> {
			timedOut.set(true);
			close();
		}, timeout.toMillis(), TimeUnit.MILLISECONDS);
		try {
			write(message);
			JsonObject result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			cancellation.check();
			return result;
		} catch (ExecutionException e) {
			cancellation.check();
			if (timedOut.get()) {
				throw new IOException("MCP request timed out; process disconnected", e);
			}
			throw new IOException("MCP process disconnected; reconnect before retrying", e);
		} catch (TimeoutException e) {
			close();
			throw new IOException("MCP request timed out; process disconnected", e);
		} catch (InterruptedException e) {
			close();
			throw e;
		} catch (IOException e) {
			cancellation.check();
			if (timedOut.get()) {
				throw new IOException("MCP request timed out; process disconnected", e);
			}
			throw e;
		} finally {
			deadline.cancel(false);
			pending.remove(key);
			cancellation.removeListener(abort);
		}
	}

	@Override
	public boolean isClosed() {
		return closed.get();
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			pending.values().forEach(future -> future.completeExceptionally(new IOException("MCP disconnected")));
			// Terminate descendants too: command launchers may spawn the actual MCP process.
			List<ProcessHandle> descendants = process.descendants().collect(java.util.stream.Collectors.toList());
			descendants.forEach(handle -> handle.destroy());
			process.destroy();
			Thread cleanup = new Thread(() -> {
				try {
					if (!process.waitFor(1, TimeUnit.SECONDS)) {
						process.destroyForcibly();
					}
					descendants.stream().filter(ProcessHandle::isAlive).forEach(handle -> handle.destroyForcibly());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}, "jadx-mcp-cleanup");
			cleanup.setDaemon(true);
			cleanup.start();
		}
	}
}
