package jadx.gui.ai.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jadx.gui.ai.AiEvidence;

/**
 * Bounded syntax observations. Only literal concatenations and same-scope scalar consts resolve.
 */
public final class JsApiExtractor {

	private static final int MAX_CHARS = 1024 * 1024;
	private static final int MAX_TOKENS = 150_000;
	private static final int MAX_REQUESTS = 250;
	private static final int MAX_CALL_TOKENS = 4096;
	private static final Set<String> AXIOS_METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
	private static final Set<String> DATA_METHODS = Set.of("POST", "PUT", "PATCH");
	private static final Set<String> NON_CALL_PREFIXES = Set.of(".", "function", "new");
	private static final Set<String> REGEX_PREFIXES =
			Set.of("return", "throw", "case", "delete", "typeof", "void", "in", "of", "instanceof",
					"yield", "await", "else", "do");
	private static final Set<String> CONTROL_PARENS = Set.of("if", "while", "for", "with", "switch", "catch");

	private JsApiExtractor() {
	}

	public static boolean isSupportedResource(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".js") || lower.endsWith(".bundle") || lower.endsWith(".html") || lower.endsWith(".htm");
	}

	public static List<ApiEndpoint> scan(String source, String resourceName) {
		return scan(source, resourceName, false);
	}

	public static List<ApiEndpoint> scan(String source, String resourceName, boolean truncated) {
		if (source == null || source.isEmpty()) {
			return List.of();
		}
		String text = source.substring(0, Math.min(source.length(), MAX_CHARS));
		if (resourceName.toLowerCase(Locale.ROOT).matches(".*\\.html?")) {
			ScriptSource script = inlineScripts(text);
			text = script.text;
			truncated |= script.truncated;
		}
		List<Token> tokens = tokenize(text);
		StaticValues values = new StaticValues(tokens);
		List<ApiEndpoint> results = new ArrayList<>();
		for (int i = 0; i < tokens.size() && results.size() < MAX_REQUESTS; i++) {
			Token token = tokens.get(i);
			if (token.kind != 'i' || i > 0 && NON_CALL_PREFIXES.contains(tokens.get(i - 1).text)) {
				continue;
			}
			String client = token.text;
			String verb = "";
			int open = i + 1;
			if ((client.equals("uni") || client.equals("axios")) && at(tokens, open, ".")) {
				String member = open + 1 < tokens.size() ? tokens.get(open + 1).text : "";
				if (client.equals("uni") && member.equals("request") || client.equals("axios") && AXIOS_METHODS.contains(member)) {
					client += '.' + member;
					verb = member.equals("request") ? "" : member.toUpperCase(Locale.ROOT);
					open += 2;
				} else {
					continue;
				}
			}
			if (!client.equals("uni.request") && !client.equals("fetch") && !client.equals("axios") && !client.startsWith("axios.")) {
				continue;
			}
			if (!at(tokens, open, "(") || tokens.get(open).end <= open
					|| tokens.get(open).end - open > MAX_CALL_TOKENS) {
				continue;
			}
			List<Range> args = split(tokens, open + 1, tokens.get(open).end);
			if (args.isEmpty()) {
				continue;
			}
			Range first = args.get(0);
			Map<String, String> constants = values.constantsAt(i);
			boolean configCall = client.equals("uni.request") || client.equals("axios") && literal(tokens, first, constants).isEmpty();
			Range config = configCall ? first : args.size() > 1 ? args.get(1) : null;
			boolean dataVerb = DATA_METHODS.contains(verb);
			if (dataVerb) {
				config = args.size() > 2 ? args.get(2) : null;
			}
			Map<String, Range> options = object(tokens, config);
			String address = literal(tokens, configCall ? property(options, "url") : first, constants);
			String method = verb;
			if (method.isEmpty()) {
				method = options == null && config != null ? "" : "GET";
				if (options != null && options.containsKey("method")) {
					method = literal(tokens, options.get("method"), constants);
				}
			}
			// A malformed/variable configuration can override every property, including method and URL.
			if (configCall && options == null) {
				method = "";
				address = "";
			}
			ApiEndpoint api = new ApiEndpoint(address.isEmpty() ? ApiEndpoint.Origin.CODE_CONTEXT : ApiEndpoint.Origin.DECLARATION,
					method, address, resourceName + ':' + token.line + ':' + token.offset + " · " + client, null);
			if (address.isEmpty()) {
				api.dynamicUrl();
			}
			if (options != null) {
				keys(tokens, property(options, "params"), api, "Query (JS)", constants);
				keys(tokens, property(options, client.equals("uni.request") ? "header" : "headers"), api, "Header (JS)", constants);
				keys(tokens, property(options, "data"), api, "Data (JS)", constants);
				String base = literal(tokens, property(options, "baseURL"), constants);
				if (!base.isEmpty()) {
					api.parameter("Configuration (JS)", "baseURL", "string", base);
				}
				Range body = property(options, "body");
				if (body != null) {
					Range json = jsonObject(tokens, body);
					keys(tokens, json, api, "Body (JS)", constants);
					api.body(json == null ? "JS body expression; value not resolved" : "JSON.stringify(object literal)");
				}
			}
			if (dataVerb && args.size() > 1) {
				keys(tokens, args.get(1), api, "Data (JS)", constants);
			}
			int callEnd = tokens.get(tokens.get(open).end).offset + 1;
			int snippetEnd = Math.min(callEnd, token.offset + 2400);
			String snippet = text.substring(token.offset, snippetEnd);
			api.source(new AiEvidence(null, api.getSources().get(0).getTitle(), snippet, AiEvidence.fingerprint(snippet),
					truncated || callEnd > snippetEnd));
			for (Range declaration : values.usedDeclarations(i, tokens.get(open).end)) {
				int start = tokens.get(declaration.start).offset;
				int end = Math.min(text.length(), tokens.get(declaration.end).offset);
				String constant = text.substring(start, Math.min(end, start + 1800));
				api.source(new AiEvidence(null, resourceName + ':' + tokens.get(declaration.start).line + ':' + start + " · const",
						constant, AiEvidence.fingerprint(constant), end - start > 1800));
			}
			results.add(api);
		}
		if (truncated || source.length() > MAX_CHARS || tokens.size() >= MAX_TOKENS || results.size() == MAX_REQUESTS) {
			results.forEach(ApiEndpoint::truncated);
		}
		return results;
	}

	static List<Token> sourceTokens(String source, String name) {
		String bounded = source.substring(0, Math.min(source.length(), MAX_CHARS));
		return tokenize(name.toLowerCase(Locale.ROOT).matches(".*\\.html?") ? inlineScripts(bounded).text : bounded);
	}

	private static ScriptSource inlineScripts(String text) {
		char[] script = new char[text.length()];
		char[] tags = new char[text.length()];
		for (int i = 0; i < script.length; i++) {
			script[i] = text.charAt(i) == '\n' ? '\n' : ' ';
			tags[i] = Character.toLowerCase(text.charAt(i));
		}
		String lower = new String(tags);
		int offset = 0;
		int comment = lower.indexOf("<!--");
		boolean truncated = false;
		while (offset < text.length()) {
			int start = lower.indexOf("<script", offset);
			if (comment >= 0 && (start < 0 || comment < start)) {
				int end = lower.indexOf("-->", comment + 4);
				if (end < 0) {
					break;
				}
				offset = end + 3;
				comment = lower.indexOf("<!--", offset);
				continue;
			}
			if (start < 0) {
				break;
			}
			int nameEnd = start + 7;
			if (nameEnd < lower.length() && lower.charAt(nameEnd) != '>' && !Character.isWhitespace(lower.charAt(nameEnd))) {
				offset = nameEnd;
				continue;
			}
			int body = lower.indexOf('>', nameEnd);
			int end = body < 0 ? -1 : lower.indexOf("</script", body + 1);
			if (end < 0) {
				if (body >= 0) {
					text.getChars(body + 1, text.length(), script, body + 1);
					truncated = true;
				}
				break;
			}
			text.getChars(body + 1, end, script, body + 1);
			offset = end + 8;
			if (comment >= 0 && comment < offset) {
				comment = lower.indexOf("<!--", offset);
			}
		}
		return new ScriptSource(new String(script), truncated);
	}

	private static Range property(Map<String, Range> values, String key) {
		return values == null ? null : values.get(key);
	}

	private static Range jsonObject(List<Token> tokens, Range value) {
		if (value.end - value.start >= 6 && at(tokens, value.start, "JSON") && at(tokens, value.start + 1, ".")
				&& at(tokens, value.start + 2, "stringify") && at(tokens, value.start + 3, "(")
				&& tokens.get(value.start + 3).end == value.end - 1) {
			Range arg = new Range(value.start + 4, value.end - 1);
			return object(tokens, arg) == null ? null : arg;
		}
		return null;
	}

	private static void keys(List<Token> tokens, Range value, ApiEndpoint api, String location, Map<String, String> constants) {
		Map<String, Range> object = object(tokens, value);
		if (object != null) {
			object.forEach((key, range) -> {
				String string = literal(tokens, range, constants);
				String scalar = scalar(tokens, range);
				api.parameter(location, key, string.isEmpty() ? "" : "string", string.isEmpty() ? scalar : string);
			});
		}
	}

	static String literal(List<Token> tokens, Range value) {
		return literal(tokens, value, Map.of());
	}

	private static String literal(List<Token> tokens, Range value, Map<String, String> constants) {
		if (value == null || value.start >= value.end || value.end - value.start > 63) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		for (int i = value.start; i < value.end; i++) {
			Token token = tokens.get(i);
			if ((i - value.start) % 2 == 1) {
				if (!token.text.equals("+")) {
					return "";
				}
			} else if (token.kind == 's') {
				text.append(token.text);
			} else if (token.kind == 'i' && constants.containsKey(token.text)) {
				text.append(constants.get(token.text));
			} else {
				return "";
			}
			if (text.length() > 4096) {
				return "";
			}
		}
		return (value.end - value.start) % 2 == 1 ? text.toString() : "";
	}

	private static String scalar(List<Token> tokens, Range range) {
		if (range.end == range.start + 1) {
			Token token = tokens.get(range.start);
			if (token.kind == 'n' || Set.of("true", "false", "null").contains(token.text)) {
				return token.text;
			}
		} else if (range.end == range.start + 2 && at(tokens, range.start, "-") && tokens.get(range.start + 1).kind == 'n') {
			return '-' + tokens.get(range.start + 1).text;
		}
		return "";
	}

	static Map<String, Range> object(List<Token> tokens, Range value) {
		if (value == null || value.start >= value.end || !at(tokens, value.start, "{")
				|| tokens.get(value.start).end != value.end - 1) {
			return null;
		}
		Map<String, Range> result = new LinkedHashMap<>();
		for (Range entry : split(tokens, value.start + 1, value.end - 1)) {
			Token key = tokens.get(entry.start);
			if (key.kind != 'i' && key.kind != 's') {
				return null; // Computed keys or spread may replace a preceding URL or method.
			}
			if (entry.end == entry.start + 1 && key.kind == 'i') {
				result.put(key.text, new Range(entry.start, entry.end));
			} else if (at(tokens, entry.start + 1, ":") && entry.start + 2 < entry.end) {
				result.put(key.text, new Range(entry.start + 2, entry.end));
			} else {
				return null;
			}
		}
		return result;
	}

	static List<Range> split(List<Token> tokens, int start, int end) {
		List<Range> result = new ArrayList<>();
		int first = start;
		for (int i = start; i < end; i++) {
			Token token = tokens.get(i);
			if (token.end > i) {
				i = token.end;
			} else if (token.text.equals(",")) {
				if (first < i) {
					result.add(new Range(first, i));
				}
				first = i + 1;
			}
		}
		if (first < end) {
			result.add(new Range(first, end));
		}
		return result;
	}

	static boolean at(List<Token> tokens, int index, String value) {
		return index >= 0 && index < tokens.size() && tokens.get(index).text.equals(value);
	}

	static List<Token> tokenize(String text) {
		List<Token> result = new ArrayList<>();
		Deque<Integer> opened = new ArrayDeque<>();
		int line = 1;
		for (int i = 0; i < text.length() && result.size() < MAX_TOKENS;) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				line += c == '\n' ? 1 : 0;
				i++;
				continue;
			}
			int start = i++;
			int startLine = line;
			if (c == '/' && i < text.length() && text.charAt(i) == '/') {
				while (i < text.length() && text.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '/' && i < text.length() && text.charAt(i) == '*') {
				i++;
				while (i < text.length() && !(text.charAt(i - 1) == '*' && text.charAt(i) == '/')) {
					line += text.charAt(i++) == '\n' ? 1 : 0;
				}
				i = Math.min(i + 1, text.length());
				continue;
			}
			char kind = 'p';
			String value = Character.toString(c);
			boolean regex = c == '/' && regexCanStart(result);
			if (c == '\'' || c == '"' || c == '`' || regex) {
				StringBuilder string = new StringBuilder();
				boolean simple = c != '`' && !regex;
				boolean closed = false;
				boolean characterClass = false;
				while (i < text.length()) {
					char next = text.charAt(i++);
					line += next == '\n' ? 1 : 0;
					if (next == c && !characterClass) {
						closed = true;
						break;
					}
					if (regex && next == '[') {
						characterClass = true;
					} else if (regex && next == ']') {
						characterClass = false;
					}
					if (next == '\\' && i < text.length()) {
						next = text.charAt(i++);
						line += next == '\n' ? 1 : 0;
						if (next != '/' && next != '\\' && next != '\'' && next != '"') {
							simple = false;
						}
					}
					string.append(next);
				}
				kind = simple && closed ? 's' : 'u';
				value = kind == 's' ? string.toString() : "?";
			} else if (Character.isDigit(c)) {
				while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
					i++;
				}
				value = text.substring(start, i);
				kind = value.matches("[0-9]+(?:\\.[0-9]+)?") ? 'n' : 'u';
			} else if (Character.isJavaIdentifierStart(c)) {
				while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
					i++;
				}
				kind = 'i';
				value = text.substring(start, i);
			}
			Token token = new Token(value, kind, startLine, start);
			int index = result.size();
			result.add(token);
			if (kind == 'p') {
				if ("({[".contains(value)) {
					opened.push(index);
				} else if (")}]".contains(value) && !opened.isEmpty()) {
					int open = opened.pop();
					if ("({[".indexOf(result.get(open).text) == ")}]".indexOf(value)) {
						result.get(open).end = index;
						token.open = open;
					} else {
						opened.clear();
					}
				}
			}
		}
		return result;
	}

	private static boolean regexCanStart(List<Token> tokens) {
		if (tokens.isEmpty()) {
			return true;
		}
		Token previous = tokens.get(tokens.size() - 1);
		if (previous.kind == 's' || previous.kind == 'u' || previous.kind == 'n') {
			return false;
		}
		if (previous.kind == 'i') {
			return REGEX_PREFIXES.contains(previous.text);
		}
		if (previous.text.equals(")")) {
			return previous.open > 0 && CONTROL_PARENS.contains(tokens.get(previous.open - 1).text);
		}
		if (previous.text.equals("]") || Character.isDigit(previous.text.charAt(0))) {
			return false;
		}
		if ((previous.text.equals("+") || previous.text.equals("-")) && tokens.size() >= 3) {
			Token paired = tokens.get(tokens.size() - 2);
			Token operand = tokens.get(tokens.size() - 3);
			if (previous.text.equals(paired.text) && previous.offset == paired.offset + 1
					&& (operand.kind == 'i' || operand.text.equals(")") || operand.text.equals("]"))) {
				return false; // Postfix ++/-- finish an expression before division.
			}
		}
		if (previous.text.equals("}")) {
			// Object literals finish an expression; statement blocks can precede a regex statement.
			return previous.open == 0 || previous.open > 0
					&& Set.of(")", "else", "try", "finally", "do", ";").contains(tokens.get(previous.open - 1).text);
		}
		return !previous.text.equals(".");
	}

	/** Deliberately excludes closure lookup, imports, object constants and mutable bindings. */
	private static final class StaticValues {
		private final List<Token> tokens;
		private final int[] scopes;
		private final Map<Integer, Map<String, Range>> declarations = new LinkedHashMap<>();
		private final Map<Integer, Map<String, String>> values = new LinkedHashMap<>();

		private StaticValues(List<Token> tokens) {
			this.tokens = tokens;
			scopes = new int[tokens.size()];
			Deque<Integer> stack = new ArrayDeque<>();
			stack.push(-1);
			for (int i = 0; i < tokens.size(); i++) {
				Token token = tokens.get(i);
				if (token.kind == 'p' && token.text.equals("}") && stack.size() > 1) {
					stack.pop();
				}
				scopes[i] = stack.peek();
				if (token.kind == 'p' && token.text.equals("{")) {
					stack.push(i);
				}
			}
			for (int i = 0; i + 4 < tokens.size(); i++) {
				if (!at(tokens, i, "const") || tokens.get(i + 1).kind != 'i' || !at(tokens, i + 2, "=")) {
					continue;
				}
				int end = i + 3;
				while (end < tokens.size() && end < i + 67 && !at(tokens, end, ";") && !at(tokens, end, ",")) {
					end++;
				}
				if (end >= tokens.size()) {
					continue;
				}
				String value = literal(tokens, new Range(i + 3, end));
				if (!value.isEmpty()) {
					String name = tokens.get(i + 1).text;
					values.computeIfAbsent(scopes[i], unused -> new LinkedHashMap<>()).put(name, value);
					declarations.computeIfAbsent(scopes[i], unused -> new LinkedHashMap<>()).put(name, new Range(i, end));
				}
			}
			for (int i = 0; i < tokens.size(); i++) {
				Token token = tokens.get(i);
				Map<String, Range> scope = declarations.get(scopes[i]);
				Range declaration = scope == null ? null : scope.get(token.text);
				if (declaration == null || i == declaration.start + 1 || at(tokens, i - 1, ".")) {
					continue;
				}
				if (at(tokens, i + 1, "=") || at(tokens, i + 1, "+") && (at(tokens, i + 2, "+") || at(tokens, i + 2, "="))
						|| at(tokens, i + 1, "-") && (at(tokens, i + 2, "-") || at(tokens, i + 2, "="))
						|| Set.of("const", "let", "var", "function", "class").contains(i > 0 ? tokens.get(i - 1).text : "")) {
					values.get(scopes[i]).remove(token.text);
				}
			}
		}

		private Map<String, String> constantsAt(int index) {
			Map<String, String> available = values.getOrDefault(scopes[index], Map.of());
			Map<String, String> result = new LinkedHashMap<>();
			available.forEach((name, value) -> {
				if (declarations.get(scopes[index]).get(name).end < index) {
					result.put(name, value);
				}
			});
			return result;
		}

		private List<Range> usedDeclarations(int start, int end) {
			Map<String, String> constants = constantsAt(start);
			List<Range> result = new ArrayList<>();
			for (int i = start; i <= end && result.size() < 4; i++) {
				if (tokens.get(i).kind == 'i' && constants.containsKey(tokens.get(i).text)) {
					Range declaration = declarations.get(scopes[start]).get(tokens.get(i).text);
					if (!result.contains(declaration)) {
						result.add(declaration);
					}
				}
			}
			return result;
		}
	}

	private static final class ScriptSource {
		private final String text;
		private final boolean truncated;

		private ScriptSource(String text, boolean truncated) {
			this.text = text;
			this.truncated = truncated;
		}
	}

	static final class Range {
		final int start;
		final int end;

		Range(int start, int end) {
			this.start = start;
			this.end = end;
		}
	}

	static final class Token {
		final String text;
		final char kind;
		final int line;
		final int offset;
		int end = -1;
		int open = -1;

		private Token(String text, char kind, int line, int offset) {
			this.text = text;
			this.kind = kind;
			this.line = line;
			this.offset = offset;
		}
	}
}
