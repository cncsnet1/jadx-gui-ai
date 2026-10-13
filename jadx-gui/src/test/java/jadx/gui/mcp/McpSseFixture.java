package jadx.gui.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import jadx.gui.burp.BurpBridge;
import jadx.gui.burp.BurpSetup;

/** Local protocol fixture. Captures no network traffic and sends no target requests. */
public final class McpSseFixture implements AutoCloseable {
	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread thread = new Thread(r, "mcp-sse-fixture");
		thread.setDaemon(true);
		return thread;
	});
	private final CountDownLatch stop = new CountDownLatch(1);
	public final CountDownLatch called = new CountDownLatch(1);
	public final AtomicInteger posts = new AtomicInteger();
	public final AtomicInteger staged = new AtomicInteger();
	public volatile String mode = "normal";
	private OutputStream stream;

	public McpSseFixture(int port) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.setExecutor(executor);
		server.createContext("/", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				stream = out;
				if (!mode.equals("no-endpoint")) {
					event("endpoint", mode.startsWith("http") ? mode : "/message?sessionId=test");
				}
				while (!stop.await(100, TimeUnit.MILLISECONDS)) {
					synchronized (this) {
						out.write(": keepalive\r\n\r\n".getBytes(StandardCharsets.UTF_8));
						out.flush();
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.createContext("/message", exchange -> {
			posts.incrementAndGet();
			JsonObject request = McpJson.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
			if (!request.has("id")) {
				return;
			}
			String method = request.get("method").getAsString();
			JsonObject result = new JsonObject();
			if (method.equals("initialize")) {
				result.addProperty("protocolVersion", "2024-11-05");
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				result.add("capabilities", capabilities);
			} else if (method.equals("tools/list")) {
				JsonArray tools = new JsonArray();
				tools.add(tool(BurpBridge.HISTORY, Map.of("regex", "string", "offset", "integer", "count", "integer")));
				tools.add(tool(BurpBridge.REPEATER, Map.of("content", "string", "targetHostname", "string", "targetPort", "integer",
						"usesHttps", "boolean", "tabName", "string")));
				result.add("tools", tools);
			} else {
				called.countDown();
				if (mode.equals("wait")) {
					return;
				}
				if (mode.equals("oversize")) {
					event("message", "x".repeat(McpJson.MAX_CHARS + 1));
					return;
				}
				if (mode.equals("malformed")) {
					event("message", "INVALID_PRIVATE_SERVER_TEXT");
					return;
				}
				if (request.getAsJsonObject("params").get("name").getAsString().equals(BurpBridge.REPEATER)) {
					staged.incrementAndGet();
					result = McpJson.text("Executed tool", false);
				} else {
					result = McpJson.text(record("GET /v1/users/PRIVATE_ID?token=PRIVATE_QUERY HTTP/1.1\r\nHost: api.example.test\r\n"
							+ "Authorization: Bearer PRIVATE_HEADER\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\nPRIVATE_BODY")
							+ "\n\n" + record("GET /v1/users/other HTTP/1.1\r\nHost: api.example.test\r\nX: ... (truncated)", "")
							+ "\n\n"
							+ record("POST /unrelated HTTP/1.1\r\nHost: api.example.test\r\n\r\n", "HTTP/1.1 401 Unauthorized\r\n\r\n"),
							false);
				}
			}
			event("message", McpJson.request(null, "notifications/progress", new JsonObject()).toString());
			event("message",
					new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(McpJson.result(request.get("id"), result)));
		});
		server.start();
	}

	public McpConnection config() {
		return BurpSetup.directConfiguration("http://127.0.0.1:" + server.getAddress().getPort());
	}

	private synchronized void event(String type, String text) throws IOException {
		stream.write(("event: " + type + "\r\n").getBytes(StandardCharsets.UTF_8));
		for (String line : text.split("\n")) {
			stream.write(("data: " + line + "\r\n").getBytes(StandardCharsets.UTF_8));
		}
		stream.write("\r\n".getBytes(StandardCharsets.UTF_8));
		stream.flush();
	}

	private static String record(String request, String response) {
		JsonObject object = new JsonObject();
		object.addProperty("request", request);
		object.addProperty("response", response);
		return object.toString();
	}

	private static JsonObject tool(String name, Map<String, String> fields) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject properties = new JsonObject();
		fields.forEach((key, value) -> {
			JsonObject property = new JsonObject();
			property.addProperty("type", value);
			properties.add(key, property);
		});
		schema.add("properties", properties);
		tool.add("inputSchema", schema);
		return tool;
	}

	@Override
	public void close() {
		stop.countDown();
		server.stop(0);
		executor.shutdownNow();
	}
}
