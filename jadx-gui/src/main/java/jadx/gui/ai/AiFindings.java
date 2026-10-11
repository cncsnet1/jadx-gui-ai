package jadx.gui.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Optional, bounded response extension. An invalid block never hides the original response. */
public final class AiFindings {
	private static final Pattern BLOCK = Pattern.compile("(?m)^```jadx-findings[ \\t]*\\r?\\n([\\s\\S]*?)^```[ \\t]*(?:\\r?\\n|$)");

	private AiFindings() {
	}

	public static String instructions() {
		return "\nAfter your readable analysis, append one fenced block tagged jadx-findings, containing JSON: "
				+ "{\"version\":1,\"findings\":[{\"kind\":\"observation|hypothesis|question\",\"title\":\"short claim\","
				+ "\"detail\":\"reasoning\",\"citations\":[{\"source\":\"S1\",\"quote\":\"exact contiguous source substring\"}],"
				+ "\"gaps\":\"missing conditions and possible counterevidence\",\"verification\":\"concrete next checks and expected observations\"}]}. "
				+ "Use the user's language for values. At most 8 useful findings, title <=160 characters, detail/gaps/verification <=2400 each, "
				+ "at most 4 citations per finding, each quote <=600 characters. Copy quotes exactly, including whitespace; never invent source IDs. "
				+ "An observation is only a model's reading of supplied code, not a verified runtime fact. "
				+ "A hypothesis must state its prerequisites and what would refute it. A question may have no citations. "
				+ "Do not fabricate evidence for missing context. Never set human review status or claim checks were executed.\n";
	}

	public static List<AiFinding> parse(String answer) {
		Matcher block = BLOCK.matcher(answer);
		if (!block.find()) {
			return Collections.emptyList();
		}
		String json = block.group(1);
		if (block.find() || json.length() > 64_000 || !boundedDepth(json)) {
			return Collections.emptyList();
		}
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			if (!root.get("version").isJsonPrimitive() || !root.get("version").getAsJsonPrimitive().isNumber()
					|| !root.get("version").getAsString().equals("1")) {
				return Collections.emptyList();
			}
			JsonArray items = root.getAsJsonArray("findings");
			if (items.isEmpty() || items.size() > 8) {
				return Collections.emptyList();
			}
			List<AiFinding> findings = new ArrayList<>();
			for (JsonElement item : items) {
				JsonObject obj = item.getAsJsonObject();
				AiFinding.Kind kind = AiFinding.Kind.valueOf(string(obj, "kind", 20, true).toUpperCase(Locale.ROOT));
				String title = string(obj, "title", 160, true);
				String detail = string(obj, "detail", 2400, true);
				String gaps = string(obj, "gaps", 2400, false);
				String verification = string(obj, "verification", 2400, false);
				JsonArray references = obj.getAsJsonArray("citations");
				if (references.size() > 4) {
					return Collections.emptyList();
				}
				List<AiFinding.Citation> citations = new ArrayList<>();
				for (JsonElement reference : references) {
					JsonObject citation = reference.getAsJsonObject();
					citations.add(new AiFinding.Citation(string(citation, "source", 20, true), string(citation, "quote", 600, false)));
				}
				// Never deserialize model-provided review fields into persistent human review state.
				findings.add(new AiFinding(kind, title, detail, citations, gaps, verification));
			}
			return Collections.unmodifiableList(findings);
		} catch (RuntimeException e) {
			return Collections.emptyList();
		}
	}

	public static String narrative(String answer) {
		if (parse(answer).isEmpty()) {
			return answer;
		}
		return BLOCK.matcher(answer).replaceFirst("").strip();
	}

	private static String string(JsonObject object, String key, int max, boolean required) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("Expected a string");
		}
		String text = value.getAsString();
		if (text.length() > max || (required && text.isBlank())) {
			throw new IllegalArgumentException("Invalid field length");
		}
		return text;
	}

	private static boolean boundedDepth(String text) {
		int depth = 0;
		boolean quoted = false;
		boolean escaped = false;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (quoted) {
				if (escaped) {
					escaped = false;
				} else if (ch == '\\') {
					escaped = true;
				} else if (ch == '"') {
					quoted = false;
				}
			} else if (ch == '"') {
				quoted = true;
			} else if (ch == '{' || ch == '[') {
				if (++depth > 12) {
					return false;
				}
			} else if (ch == '}' || ch == ']') {
				depth--;
			}
		}
		return true;
	}
}
