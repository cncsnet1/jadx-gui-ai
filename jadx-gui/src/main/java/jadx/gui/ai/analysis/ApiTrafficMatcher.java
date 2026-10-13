package jadx.gui.ai.analysis;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.gui.burp.BurpBridge;
import jadx.gui.burp.BurpExchange;
import jadx.gui.burp.BurpText;

/** Correlates request features, never claims that a particular method actually executed. */
public final class ApiTrafficMatcher {
	public enum Level {
		FEATURES, CANDIDATE;

		public String label() {
			return BurpText.str(this == FEATURES ? "features" : "candidate");
		}
	}

	public static final class Match {
		private final Level level;
		private final String route;
		private final String reason;

		Match(Level level, String route, String reason) {
			this.level = level;
			this.route = route;
			this.reason = reason;
		}

		public Level level() {
			return level;
		}

		public String route() {
			return route;
		}

		public String reason() {
			return reason;
		}
	}

	private ApiTrafficMatcher() {
	}

	public static Match match(ApiEndpoint endpoint, ApiDossier dossier, BurpExchange exchange) {
		if (endpoint == null || endpoint.isDynamicUrl() || endpoint.isTruncated()
				|| endpoint.getAddress().isBlank() || endpoint.getOrigin() == ApiEndpoint.Origin.CODE_CONTEXT
				|| (!endpoint.getMethod().isBlank() && !endpoint.getMethod().equals(exchange.method()))) {
			return null;
		}
		// Keep literal values in the inventory. Correlation compares method, authority and raw path only;
		// query values, fragments and URL userinfo do not establish an executed request or authentication
		// match.
		String address = endpoint.getAddress();
		List<URI> targets = new ArrayList<>();
		try {
			URI route = uri(address.replace("{", "%7B").replace("}", "%7D"));
			if (route.isAbsolute() || route.getHost() != null) {
				targets.add(route);
			} else if (dossier != null && !dossier.getBindings().isEmpty()) {
				for (ApiDossier.Binding binding : dossier.getBindings()) {
					if (!binding.getBaseUrl().isBlank()) {
						try {
							targets.add(uri(binding.getBaseUrl()).resolve(route));
						} catch (IllegalArgumentException ignored) {
							// Invalid or unresolved base URLs cannot establish an authority.
						}
					}
				}
			}
			if (!targets.isEmpty()) {
				for (URI target : targets) {
					if (target.getHost() == null || !target.getHost().equalsIgnoreCase(exchange.host())
							|| !("http".equals(target.getScheme()) || "https".equals(target.getScheme()))
							|| !BurpBridge.inScope(exchange, target)) {
						continue;
					}
					String template = path(target);
					if (matchesPath(template, exchange.path())) {
						return new Match(endpoint.getMethod().isBlank() ? Level.CANDIDATE : Level.FEATURES, template,
								BurpText.str(endpoint.getMethod().isBlank() ? "method_unknown" : "features_reason"));
					}
				}
				return null;
			}
			String template = path(route);
			if (!template.startsWith("/")) {
				template = '/' + template;
			}
			return matchesPath(template, exchange.path()) ? new Match(Level.CANDIDATE, template, BurpText.str("host_unknown")) : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static URI uri(String value) {
		URI parsed = URI.create(value);
		String scheme = parsed.getScheme();
		return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
				? URI.create(scheme.toLowerCase(java.util.Locale.ROOT) + value.substring(scheme.length()))
				: parsed;
	}

	private static String path(URI uri) {
		String path = uri.getRawPath();
		return path == null || path.isEmpty() ? "/" : path.replace("%7B", "{").replace("%7D", "}");
	}

	private static boolean matchesPath(String template, String actual) {
		if (template.length() > 2000 || template.contains("[redacted]") || template.contains("[long literal")) {
			return false;
		}
		Matcher placeholders = Pattern.compile("\\{[A-Za-z_][A-Za-z0-9_]*}").matcher(template);
		StringBuilder regex = new StringBuilder("^");
		int end = 0;
		int parameterSegmentEnd = -1;
		while (placeholders.find()) {
			// Multiple variable-width parameters in one segment are ambiguous and can cause
			// excessive regex backtracking on APK-controlled route templates.
			if (placeholders.start() < parameterSegmentEnd) {
				return false;
			}
			int slash = template.indexOf('/', placeholders.start());
			parameterSegmentEnd = slash < 0 ? template.length() : slash;
			regex.append(Pattern.quote(template.substring(end, placeholders.start()))).append("[^/]+");
			end = placeholders.end();
		}
		regex.append(Pattern.quote(template.substring(end))).append('$');
		return actual.matches(regex.toString());
	}
}
