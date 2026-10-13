package jadx.gui.ai.analysis;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative outbound snapshots: retain structure, mask literal contents and URL query values.
 */
public final class AnalysisRedactor {
	private static final Pattern PEM = Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
	private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'");
	private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9_+/=-]{24,}");
	private static final Set<String> LABELS = Set.of("password", "passwd", "username", "user", "token", "access_token",
			"authorization", "bearer", "secret", "client_secret", "api_key", "login", "true", "false", "get", "post", "put", "delete");

	private AnalysisRedactor() {
	}

	public static String redact(String code) {
		String clean = PEM.matcher(code).replaceAll("[PRIVATE KEY REDACTED]");
		clean = clean.replaceAll("(?s)(<string\\b[^>]*>)[^<]*(</string>)", "$1[literal redacted]$2");
		clean = clean.replaceAll("(?im)^(?![a-z][a-z0-9+.-]*://)([\\w.-]+\\s*[:=]\\s*)[^\\r\\n]+$", "$1[literal redacted]");
		Matcher strings = STRING.matcher(clean);
		StringBuffer out = new StringBuffer();
		while (strings.find()) {
			String value = strings.group().substring(1, strings.group().length() - 1);
			String replacement = LABELS.contains(value.toLowerCase(Locale.ROOT)) ? value
					: value.startsWith("http://") || value.startsWith("https://") ? safeUrl(value) : "[literal redacted]";
			strings.appendReplacement(out, Matcher.quoteReplacement("\"" + replacement + "\""));
		}
		strings.appendTail(out);
		Matcher urls = URL.matcher(out);
		StringBuffer result = new StringBuffer();
		while (urls.find()) {
			urls.appendReplacement(result, Matcher.quoteReplacement(safeUrl(urls.group())));
		}
		urls.appendTail(result);
		return LONG_TOKEN.matcher(result).replaceAll("[long literal redacted]");
	}

	static String safeUrl(String url) {
		String clean = url.replaceFirst("(?i)(https?://)[^/?#\\s]+@", "$1[redacted]@");
		int query = clean.indexOf('?');
		if (query >= 0) {
			clean = clean.substring(0, query) + "?[redacted]";
		}
		int fragment = clean.indexOf('#');
		return fragment >= 0 ? clean.substring(0, fragment) : clean;
	}
}
