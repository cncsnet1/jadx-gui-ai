package jadx.gui.mcp;

import java.io.IOException;
import java.io.Reader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class McpJson {

	static final String VERSION = "2025-11-25";
	static final int MAX_CHARS = 1_000_000;

	private McpJson() {
	}

	static boolean supported(String version) {
		return VERSION.equals(version) || "2025-06-18".equals(version) || "2025-03-26".equals(version);
	}

	static JsonObject object(String json) throws IOException {
		try {
			return JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IOException("Invalid MCP JSON object", e);
		}
	}

	static String string(JsonObject object, String key, String fallback) {
		JsonElement value = object.get(key);
		if (value == null) {
			return fallback;
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("Expected string: " + key);
		}
		return value.getAsString();
	}

	static int integer(JsonObject object, String key, int fallback, int min, int max) {
		JsonElement value = object.get(key);
		if (value == null) {
			return fallback;
		}
		try {
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				throw new IllegalArgumentException();
			}
			int number = value.getAsBigDecimal().intValueExact();
			if (number >= min && number <= max) {
				return number;
			}
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid integer: " + key);
		}
		throw new IllegalArgumentException("Out of range: " + key);
	}

	static JsonObject request(JsonElement id, String method, JsonObject params) {
		JsonObject object = new JsonObject();
		object.addProperty("jsonrpc", "2.0");
		if (id != null) {
			object.add("id", id);
		}
		object.addProperty("method", method);
		object.add("params", params);
		return object;
	}

	static JsonObject result(JsonElement id, JsonObject result) {
		JsonObject object = new JsonObject();
		object.addProperty("jsonrpc", "2.0");
		object.add("id", id);
		object.add("result", result);
		return object;
	}

	static JsonObject error(JsonElement id, int code, String message) {
		JsonObject object = new JsonObject();
		object.addProperty("jsonrpc", "2.0");
		object.add("id", id);
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		object.add("error", error);
		return object;
	}

	static JsonObject text(String value, boolean error) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", value);
		JsonArray items = new JsonArray();
		items.add(content);
		JsonObject result = new JsonObject();
		result.add("content", items);
		result.addProperty("isError", error);
		return result;
	}

	static String read(Reader reader) throws IOException {
		StringBuilder text = new StringBuilder();
		char[] buffer = new char[4096];
		int count;
		while ((count = reader.read(buffer)) != -1) {
			text.append(buffer, 0, count);
			if (text.length() > MAX_CHARS) {
				throw new IOException("MCP message exceeds size limit");
			}
		}
		return text.toString();
	}

	static String line(Reader reader) throws IOException {
		StringBuilder text = new StringBuilder();
		int ch;
		while ((ch = reader.read()) != -1) {
			if (ch == '\n') {
				return text.toString();
			}
			text.append((char) ch);
			if (text.length() > MAX_CHARS) {
				throw new IOException("MCP line exceeds size limit");
			}
		}
		return text.length() == 0 ? null : text.toString();
	}
}
