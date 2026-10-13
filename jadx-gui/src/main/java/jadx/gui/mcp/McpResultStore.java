package jadx.gui.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import jadx.gui.ai.AiEvidence;

/** Retains complete text/structured results for one model run; pages are valid JSON envelopes. */
final class McpResultStore {
	private final Map<String, String> results = new LinkedHashMap<>();
	private int characters;

	String retain(JsonObject result) {
		String encoded = result.toString();
		if (encoded.length() <= 12_000) {
			return encoded;
		}
		String id = "R-" + AiEvidence.fingerprint(encoded);
		if (!results.containsKey(id)) {
			if (results.size() >= 8 || characters + encoded.length() > 2_000_000) {
				JsonObject error = new JsonObject();
				error.addProperty("isError", true);
				error.addProperty("message",
						"Local retention budget reached; request a smaller external result. No complete result retained.");
				return error.toString();
			}
			results.put(id, encoded);
			characters += encoded.length();
		}
		return read(id, 0);
	}

	String read(String id, int offset) {
		String result = results.get(id);
		if (result == null || offset < 0 || offset > result.length()) {
			throw new IllegalArgumentException("Unknown retained result or invalid offset in this model run");
		}
		int end = Math.min(result.length(), offset + 3000);
		if (end < result.length() && Character.isHighSurrogate(result.charAt(end - 1))) {
			end--;
		}
		JsonObject page = new JsonObject();
		page.addProperty("resultId", id);
		page.addProperty("offset", offset);
		page.addProperty("totalCharacters", result.length());
		page.addProperty("format", "UTF-16 slice of complete result JSON; concatenate fragments before parsing");
		page.addProperty("fragment", result.substring(offset, end));
		page.addProperty("truncated", end < result.length());
		if (end < result.length()) {
			page.addProperty("nextOffset", end);
		}
		return page.toString();
	}
}
