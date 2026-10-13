package jadx.gui.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import jadx.gui.ai.AiCancellation;

/** A manually connected MCP tools client. Requests are serialized within each connection. */
public final class McpClient implements AutoCloseable {

	private final McpTransport transport;
	private final McpConnection configuration;
	private volatile List<JsonObject> tools = List.of();
	private long sequence;
	private volatile boolean closed;

	private McpClient(McpConnection configuration, Duration timeout) throws IOException {
		configuration.validate();
		this.configuration = configuration.copy();
		transport = "stdio".equals(configuration.transport)
				? new McpStdioTransport(configuration, timeout)
				: "sse".equals(configuration.transport) ? new McpSseTransport(configuration, timeout)
				: new McpHttpTransport(configuration, timeout);
	}

	public static McpClient connect(McpConnection configuration, AiCancellation cancellation) throws IOException, InterruptedException {
		return connect(configuration, cancellation, Duration.ofSeconds(60));
	}

	static McpClient connect(McpConnection configuration, AiCancellation cancellation, Duration timeout)
			throws IOException, InterruptedException {
		cancellation.check();
		McpClient client = new McpClient(configuration, timeout);
		try {
			client.initialize(cancellation);
			return client;
		} catch (IOException | InterruptedException | RuntimeException e) {
			client.close();
			throw e;
		}
	}

	private void initialize(AiCancellation cancellation) throws IOException, InterruptedException {
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", McpJson.VERSION);
		params.add("capabilities", new JsonObject());
		JsonObject info = new JsonObject();
		info.addProperty("name", "jadx-gui-ai");
		info.addProperty("version", "1.0");
		params.add("clientInfo", info);
		JsonObject result = request("initialize", params, cancellation);
		String version = McpJson.string(result, "protocolVersion", "");
		if (!(McpJson.supported(version) || (!"http".equals(configuration.transport) && "2024-11-05".equals(version)))) {
			throw new IOException("Unsupported MCP protocol version");
		}
		if (!result.has("capabilities") || !result.getAsJsonObject("capabilities").has("tools")) {
			throw new IOException("This MCP server does not provide tools");
		}
		transport.version(version);
		transport.exchange(McpJson.request(null, "notifications/initialized", new JsonObject()), cancellation);
		refresh(cancellation);
	}

	public synchronized void refresh(AiCancellation cancellation) throws IOException, InterruptedException {
		List<JsonObject> catalog = new ArrayList<>();
		Set<String> names = new HashSet<>();
		Set<String> cursors = new HashSet<>();
		String cursor = null;
		int size = 0;
		do {
			JsonObject params = new JsonObject();
			if (cursor != null) {
				params.addProperty("cursor", cursor);
			}
			JsonObject result = request("tools/list", params, cancellation);
			JsonArray items = result.getAsJsonArray("tools");
			if (items == null) {
				throw new IOException("MCP server returned no tool catalog");
			}
			for (JsonElement item : items) {
				JsonObject tool = item.getAsJsonObject();
				String name = McpJson.string(tool, "name", "");
				if (!name.matches("[A-Za-z0-9_.-]{1,128}") || !names.add(name)
						|| !tool.has("inputSchema") || !tool.get("inputSchema").isJsonObject()
						|| !"object".equals(McpJson.string(tool.getAsJsonObject("inputSchema"), "type", ""))) {
					throw new IOException("MCP tool catalog has invalid or duplicate tools");
				}
				size += tool.toString().length();
				catalog.add(tool.deepCopy());
				if (catalog.size() > 1024 || size > 2_000_000) {
					throw new IOException("MCP tool catalog exceeds limit (1024 tools / 2000000 characters)");
				}
			}
			cursor = McpJson.string(result, "nextCursor", null);
			if (cursor != null && (!cursors.add(cursor) || cursors.size() > 20)) {
				throw new IOException("Invalid MCP pagination");
			}
		} while (cursor != null);
		tools = List.copyOf(catalog);
	}

	public List<JsonObject> tools() {
		List<JsonObject> copy = new ArrayList<>();
		tools.forEach(tool -> copy.add(tool.deepCopy()));
		return copy;
	}

	public McpConnection configuration() {
		return configuration.copy();
	}

	public synchronized JsonObject call(String name, JsonObject arguments, AiCancellation cancellation)
			throws IOException, InterruptedException {
		if (tools.stream().noneMatch(tool -> tool.get("name").getAsString().equals(name))) {
			throw new IOException("Tool is not in the connected server's catalog");
		}
		if (arguments.toString().length() > 32_000) {
			throw new IOException("MCP tool arguments exceed size limit");
		}
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", arguments.deepCopy());
		return request("tools/call", params, cancellation);
	}

	private JsonObject request(String method, JsonObject params, AiCancellation cancellation) throws IOException, InterruptedException {
		if (closed) {
			throw new IOException("MCP connection closed; reconnect before retrying");
		}
		JsonPrimitive id = new JsonPrimitive(++sequence);
		JsonObject result = transport.exchange(McpJson.request(id, method, params), cancellation);
		if (!"2.0".equals(McpJson.string(result, "jsonrpc", "")) || !id.equals(result.get("id"))) {
			throw new IOException("Invalid MCP response envelope");
		}
		if (result.has("error")) {
			throw new IOException("MCP server rejected " + method); // Never echo arbitrary server error text.
		}
		if (!result.has("result") || !result.get("result").isJsonObject()) {
			throw new IOException("Missing MCP result");
		}
		return result.getAsJsonObject("result");
	}

	public boolean isClosed() {
		return closed || transport.isClosed();
	}

	@Override
	public void close() {
		closed = true;
		transport.close();
	}
}
