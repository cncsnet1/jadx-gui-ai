package jadx.gui.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Separate process: a real newline-delimited stdio peer, with deterministic failure modes. */
public final class McpProcessFixture {

	private McpProcessFixture() {
	}

	public static void main(String[] args) throws Exception {
		String mode = args.length == 0 ? "normal" : args[0];
		try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = input.readLine()) != null) {
				JsonObject request = JsonParser.parseString(line).getAsJsonObject();
				if (!request.has("id") || !request.has("method")) {
					continue;
				}
				JsonObject result = new JsonObject();
				switch (request.get("method").getAsString()) {
					case "initialize":
						result.addProperty("protocolVersion", mode.equals("version") ? "unknown" : "2024-11-05");
						JsonObject capabilities = new JsonObject();
						capabilities.add("tools", new JsonObject());
						result.add("capabilities", capabilities);
						JsonObject info = new JsonObject();
						info.addProperty("name", "fixture");
						info.addProperty("version", "1");
						result.add("serverInfo", info);
						break;
					case "tools/list":
						JsonArray tools = new JsonArray();
						JsonObject tool = new JsonObject();
						tool.addProperty("name", request.getAsJsonObject("params").has("cursor") ? "second" : "echo");
						tool.addProperty("description", "Local test tool");
						JsonObject schema = new JsonObject();
						schema.addProperty("type", "object");
						schema.add("properties", new JsonObject());
						tool.add("inputSchema", schema);
						tools.add(tool);
						result.add("tools", tools);
						if ((mode.equals("pages") && !request.getAsJsonObject("params").has("cursor")) || mode.equals("loop")) {
							result.addProperty("nextCursor", "next");
						}
						break;
					case "tools/call":
						if (mode.equals("wait")) {
							Thread.sleep(30_000);
						}
						if (mode.equals("exit")) {
							return;
						}
						if (mode.equals("oversize")) {
							System.out.println("x".repeat(1_000_001));
							System.out.flush();
							continue;
						}
						JsonObject text = new JsonObject();
						text.addProperty("type", "text");
						text.addProperty("text", "fixture-result: " + request.getAsJsonObject("params").get("arguments"));
						JsonArray content = new JsonArray();
						content.add(text);
						result.add("content", content);
						result.addProperty("isError", false);
						break;
					default:
						throw new IllegalArgumentException("Unexpected method");
				}
				JsonObject response = new JsonObject();
				response.addProperty("jsonrpc", "2.0");
				response.add("id", request.get("id"));
				response.add("result", result);
				System.out.println(response);
				System.out.flush();
			}
		}
	}
}
