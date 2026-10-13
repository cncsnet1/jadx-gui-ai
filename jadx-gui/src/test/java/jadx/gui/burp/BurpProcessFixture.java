package jadx.gui.burp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Real stdio process implementing the documented PortSwigger tool shapes, without Burp or target
 * I/O.
 */
public final class BurpProcessFixture {
	private BurpProcessFixture() {
	}

	public static void main(String[] args) throws Exception {
		String mode = args.length == 0 ? "normal" : args[0];
		int reads = 0;
		int staged = 0;
		JsonObject stagedArgs = new JsonObject();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
				PrintWriter writer = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)) {
			String line;
			while ((line = reader.readLine()) != null) {
				JsonObject request = JsonParser.parseString(line).getAsJsonObject();
				if (!request.has("id")) {
					continue;
				}
				String method = request.get("method").getAsString();
				JsonObject result = new JsonObject();
				if ("initialize".equals(method)) {
					result.addProperty("protocolVersion", "2024-11-05");
					result.add("capabilities", JsonParser.parseString("{\"tools\":{}}"));
				} else if ("tools/list".equals(method)) {
					JsonArray tools = new JsonArray();
					JsonObject history = tool(BurpBridge.HISTORY, Map.of("regex", "string", "count", "integer", "offset", "integer"));
					if ("schema".equals(mode)) {
						history.getAsJsonObject("inputSchema").getAsJsonObject("properties").getAsJsonObject("offset").addProperty("type",
								"string");
					}
					tools.add(history);
					tools.add(tool(BurpBridge.REPEATER, Map.of("content", "string", "targetHostname", "string",
							"targetPort", "integer", "usesHttps", "boolean", "tabName", "string")));
					tools.add(tool("send_http1_request", Map.of("content", "string")));
					tools.add(tool("fixture_stats", Map.of()));
					result.add("tools", tools);
				} else {
					JsonObject params = request.getAsJsonObject("params");
					String name = params.get("name").getAsString();
					JsonObject values = params.getAsJsonObject("arguments");
					if (BurpBridge.HISTORY.equals(name)) {
						reads++;
						if ("wait".equals(mode)) {
							Thread.sleep(30_000);
						}
						int offset = values.get("offset").getAsInt();
						if ("deny".equals(mode)) {
							result = text("HTTP history access denied by Burp Suite");
						} else if (offset > 0) {
							result = text("Reached end of items");
						} else {
							String record = record("GET /v1/users/PRIVATE_ID?token=PRIVATE_QUERY HTTP/1.1\r\nHost: api.example.test\r\n"
									+ "Authorization: Bearer PRIVATE_HEADER\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\nPRIVATE_BODY");
							if ("pages".equals(mode)) {
								result = text(String.join("\n\n", java.util.Collections.nCopies(50, record)));
							} else {
								result = text(record + "\n\n"
										+ record("GET /v1/users/other HTTP/1.1\r\nHost: api.example.test\r\nX: ... (truncated)", "")
										+ "\n\n" + record("POST /unrelated HTTP/1.1\r\nHost: api.example.test\r\n\r\n",
												"HTTP/1.1 401 Unauthorized\r\n\r\n"));
							}
						}
					} else if (BurpBridge.REPEATER.equals(name)) {
						staged++;
						stagedArgs = values.deepCopy();
						result = text("unknown".equals(mode) ? "Unfamiliar result" : "Executed tool");
					} else if ("fixture_stats".equals(name)) {
						result.addProperty("reads", reads);
						result.addProperty("staged", staged);
						result.add("arguments", stagedArgs);
					} else {
						throw new AssertionError("Unexpected tool invocation: " + name);
					}
				}
				JsonObject envelope = new JsonObject();
				envelope.addProperty("jsonrpc", "2.0");
				envelope.add("id", request.get("id"));
				envelope.add("result", result);
				writer.println(envelope);
			}
		}
	}

	private static JsonObject tool(String name, Map<String, String> fields) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();
		fields.forEach((key, type) -> {
			JsonObject field = new JsonObject();
			field.addProperty("type", type);
			properties.add(key, field);
			required.add(key);
		});
		schema.add("properties", properties);
		schema.add("required", required);
		tool.add("inputSchema", schema);
		return tool;
	}

	private static JsonObject text(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		JsonArray content = new JsonArray();
		content.add(block);
		JsonObject result = new JsonObject();
		result.add("content", content);
		return result;
	}

	private static String record(String request, String response) {
		JsonObject record = new JsonObject();
		record.addProperty("request", request);
		record.addProperty("response", response);
		return record.toString();
	}
}
