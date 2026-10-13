package jadx.gui.burp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonStreamParser;

import jadx.gui.ai.AiCancellation;
import jadx.gui.mcp.McpClient;

/** Adapter for the published PortSwigger MCP schemas, with explicit user-selected origin. */
public final class BurpBridge {
	public static final int PAGE_SIZE = 50;
	public static final String HISTORY = "get_proxy_http_history_regex";
	public static final String REPEATER = "create_repeater_tab";

	public static final class Page {
		private final List<BurpExchange> exchanges;
		private final int nextOffset;
		private final boolean end;
		private final int skipped;

		Page(List<BurpExchange> exchanges, int nextOffset, boolean end, int skipped) {
			this.exchanges = exchanges;
			this.nextOffset = nextOffset;
			this.end = end;
			this.skipped = skipped;
		}

		public List<BurpExchange> exchanges() {
			return exchanges;
		}

		public int nextOffset() {
			return nextOffset;
		}

		public boolean end() {
			return end;
		}

		public int skipped() {
			return skipped;
		}
	}

	private BurpBridge() {
	}

	public static URI origin(String text) {
		URI uri = URI.create(text.trim());
		if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
				|| uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
				|| !(uri.getPath().isEmpty() || "/".equals(uri.getPath())) || uri.getPort() == 0 || uri.getPort() > 65535) {
			throw new IllegalArgumentException(BurpText.str("invalid_origin"));
		}
		return uri;
	}

	public static int port(URI origin) {
		return origin.getPort() >= 0 ? origin.getPort() : "https".equals(origin.getScheme()) ? 443 : 80;
	}

	public static boolean inScope(BurpExchange exchange, URI origin) {
		int observedPort = exchange.port() >= 0 ? exchange.port()
				: exchange.scheme().isEmpty() ? -1 : "https".equals(exchange.scheme()) ? 443 : 80;
		return exchange.host().equalsIgnoreCase(origin.getHost())
				&& (observedPort < 0 || observedPort == port(origin))
				&& (exchange.scheme().isEmpty() || exchange.scheme().equals(origin.getScheme()));
	}

	public static boolean supports(McpClient client) {
		return !client.isClosed() && client.tools().stream().anyMatch(t -> HISTORY.equals(t.get("name").getAsString()));
	}

	public static Page read(McpClient client, URI origin, int offset, AiCancellation token) throws IOException, InterruptedException {
		if (offset < 0 || offset >= 500) {
			throw new IllegalArgumentException("History offset exceeds session bounds");
		}
		requireSchema(client, HISTORY, Map.of("regex", "string", "count", "integer", "offset", "integer"));
		JsonObject args = new JsonObject();
		args.addProperty("regex", "(?i)" + Pattern.quote(origin.getHost()));
		args.addProperty("count", PAGE_SIZE);
		args.addProperty("offset", offset);
		return parse(client.call(HISTORY, args, token), origin, offset);
	}

	public static Page parse(JsonObject result, URI origin, int offset) throws IOException {
		if (result.has("isError") && result.get("isError").getAsBoolean()) {
			throw new IOException(BurpText.str("read_failed"));
		}
		List<BurpExchange> exchanges = new ArrayList<>();
		int count = 0;
		int skipped = 0;
		boolean end = false;
		try {
			if (!result.has("content") || !result.get("content").isJsonArray() || result.getAsJsonArray("content").isEmpty()) {
				throw new IllegalArgumentException();
			}
			for (JsonElement block : result.getAsJsonArray("content")) {
				JsonObject content = block.getAsJsonObject();
				if (!"text".equals(content.get("type").getAsString())) {
					throw new IllegalArgumentException();
				}
				String text = content.get("text").getAsString();
				if (text.equals("Reached end of items")) {
					end = true;
					continue;
				}
				if (!text.stripLeading().startsWith("{") || text.length() > 750_000) {
					throw new IllegalArgumentException();
				}
				JsonStreamParser parser = new JsonStreamParser(text);
				while (parser.hasNext()) {
					if (++count > PAGE_SIZE) {
						throw new IllegalArgumentException();
					}
					JsonObject record = parser.next().getAsJsonObject();
					try {
						BurpExchange exchange = BurpExchange.parse(record.get("request").getAsString(),
								record.has("response") && !record.get("response").isJsonNull() ? record.get("response").getAsString() : "");
						if (inScope(exchange, origin)) {
							exchanges.add(exchange);
						} else {
							skipped++;
						}
					} catch (RuntimeException malformedRecord) {
						skipped++;
					}
				}
			}
		} catch (RuntimeException e) {
			// Error strings and malformed snapshots must not be interpreted as an empty successful scan.
			throw new IOException(BurpText.str("read_failed"));
		}
		return new Page(List.copyOf(exchanges), offset + count, end || count < PAGE_SIZE, skipped);
	}

	public static JsonObject repeaterArguments(BurpExchange exchange, URI origin) {
		if (!exchange.canStage() || !inScope(exchange, origin)) {
			throw new IllegalArgumentException(BurpText.str("cannot_stage"));
		}
		JsonObject args = new JsonObject();
		args.addProperty("content", exchange.rawRequest());
		args.addProperty("targetHostname", origin.getHost());
		args.addProperty("targetPort", port(origin));
		args.addProperty("usesHttps", "https".equals(origin.getScheme()));
		args.addProperty("tabName", "JADX · " + exchange.method());
		return args;
	}

	public static void stage(McpClient client, BurpExchange exchange, URI origin, AiCancellation token)
			throws IOException, InterruptedException {
		requireSchema(client, REPEATER, Map.of("content", "string", "targetHostname", "string", "targetPort", "integer",
				"usesHttps", "boolean", "tabName", "string"));
		JsonObject result = client.call(REPEATER, repeaterArguments(exchange, origin), token);
		if (result.has("isError") && result.get("isError").getAsBoolean()) {
			throw new IOException(BurpText.str("stage_failed"));
		}
		if (!result.has("content") || !result.get("content").isJsonArray()
				|| result.getAsJsonArray("content").asList().stream().noneMatch(b -> b.isJsonObject()
						&& b.getAsJsonObject().has("text") && "Executed tool".equals(b.getAsJsonObject().get("text").getAsString()))) {
			throw new IOException(BurpText.str("stage_unknown"));
		}
	}

	private static void requireSchema(McpClient client, String name, Map<String, String> fields) throws IOException {
		JsonObject tool = client.tools().stream().filter(t -> name.equals(t.get("name").getAsString())).findFirst()
				.orElseThrow(() -> new IOException(BurpText.str("incompatible")));
		try {
			JsonObject schema = tool.getAsJsonObject("inputSchema");
			JsonObject properties = schema.getAsJsonObject("properties");
			for (Map.Entry<String, String> field : fields.entrySet()) {
				JsonElement type = properties.getAsJsonObject(field.getKey()).get("type");
				boolean matches = type.isJsonPrimitive()
						? type.getAsString().equals(field.getValue())
						: type.isJsonArray()
								&& type.getAsJsonArray().asList().stream().anyMatch(t -> field.getValue().equals(t.getAsString()));
				if (!matches) {
					throw new IllegalArgumentException();
				}
			}
			Set<String> provided = fields.keySet();
			if (schema.has("required")) {
				for (JsonElement required : schema.getAsJsonArray("required")) {
					if (!provided.contains(required.getAsString())) {
						throw new IllegalArgumentException();
					}
				}
			}
		} catch (RuntimeException e) {
			throw new IOException(BurpText.str("incompatible"));
		}
	}
}
