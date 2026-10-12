package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/** An observed HTTP declaration or lead. Never implies a live or reachable server endpoint. */
public final class ApiEndpoint {
	public enum Origin {
		DECLARATION, ADDRESS, CODE_CONTEXT;

		public String label() {
			return AiText.str("api.origin." + name().toLowerCase(Locale.ROOT));
		}
	}

	public static final class Parameter {
		private final String location;
		private final String name;
		private final String type;

		private Parameter() {
			this("", "", "");
		}

		Parameter(String location, String name, String type) {
			this.location = location;
			this.name = label(name);
			this.type = label(type);
		}

		public String getLocation() {
			return location;
		}

		public String getName() {
			return name;
		}

		public String getType() {
			return type;
		}

		@Override
		public String toString() {
			return location + " · " + name + (type.isEmpty() ? "" : " : " + type);
		}
	}

	private static final Pattern AUTHORITY = Pattern.compile("(?i)^https?://([^/]+)(.*)$");
	private String id;
	private Origin origin;
	private String method;
	private String address;
	private String host = "";
	private String body = "";
	private boolean dynamicUrl;
	private boolean truncated;
	private int occurrences;
	private List<Parameter> parameters = new ArrayList<>();
	private List<String> authHints = new ArrayList<>();
	private List<AiEvidence> sources = new ArrayList<>();

	private ApiEndpoint() {
	}

	ApiEndpoint(Origin origin, String method, String rawAddress, String title, JadxNodeRef ref) {
		this.origin = origin;
		this.method = method.matches("[A-Za-z][A-Za-z0-9_-]{0,23}") ? method.toUpperCase(Locale.ROOT) : "";
		String route = rawAddress.split("#", 2)[0];
		int query = route.indexOf('?');
		if (query >= 0) {
			String[] pairs = route.substring(query + 1).split("&", 82);
			for (String pair : pairs) {
				String name = pair.split("=", 2)[0];
				if (!name.isEmpty()) {
					parameter("Query (URL)", name, "");
				}
			}
			route = route.substring(0, query);
		}
		Matcher absolute = AUTHORITY.matcher(route);
		if (absolute.matches()) {
			String authority = absolute.group(1);
			int userInfo = authority.lastIndexOf('@');
			if (userInfo >= 0) {
				authority = authority.substring(userInfo + 1);
				authHints.add("URL userinfo [redacted]");
			}
			host = label(authority.toLowerCase(Locale.ROOT));
			route = route.substring(0, route.indexOf("://") + 3) + authority + absolute.group(2);
		}
		address = AnalysisRedactor.redact(route);
		if (address.length() > 2000) {
			address = address.substring(0, 2000);
			truncated = true;
		}
		sources.add(new AiEvidence(ref, title, "", "", false));
		occurrences = 1;
	}

	static String label(String text) {
		String clean = text.replaceAll("[\\p{Cntrl}]", " ").replaceAll("[A-Za-z0-9_+/=-]{24,}", "[long literal redacted]");
		return clean.length() > 160 ? clean.substring(0, 160) + "…" : clean;
	}

	void parameter(String location, String name, String type) {
		if (parameters.size() >= 80) {
			truncated = true;
			return;
		}
		Parameter param = new Parameter(location, name, type);
		if (parameters.stream().noneMatch(p -> p.toString().equals(param.toString()))) {
			parameters.add(param);
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if ((lower.equals("authorization") || lower.equals("cookie") || lower.contains("api-key")
				|| lower.contains("api_key") || lower.contains("token")) && authHints.size() < 12) {
			String hint = location + " · " + param.name;
			if (!authHints.contains(hint)) {
				authHints.add(hint);
			}
		}
	}

	void header(String declaration) {
		int colon = declaration.indexOf(':');
		String name = colon < 0 ? declaration : declaration.substring(0, colon).trim();
		parameter("Header (static)", name, "");
		if (name.equalsIgnoreCase("Authorization") && colon >= 0 && authHints.size() < 12) {
			String value = declaration.substring(colon + 1).trim();
			if (value.matches("(?is)Bearer\\s+.*")) {
				authHints.add("Bearer [redacted]");
			} else if (value.matches("(?is)Basic\\s+.*")) {
				authHints.add("Basic [redacted]");
			}
		}
	}

	void body(String value) {
		body = label(value);
	}

	void dynamicUrl() {
		dynamicUrl = true;
	}

	void truncated() {
		truncated = true;
	}

	void id(String value) {
		id = value;
	}

	String identity() {
		return origin + "\n" + method + "\n" + address + "\n" + parameterSummary()
				+ (origin == Origin.ADDRESS ? "" : "\n" + sources.get(0).getTitle());
	}

	void merge(ApiEndpoint other) {
		occurrences += other.occurrences;
		truncated |= other.truncated;
		for (String hint : other.authHints) {
			if (authHints.size() < 12 && !authHints.contains(hint)) {
				authHints.add(hint);
			}
		}
		for (AiEvidence source : other.sources) {
			if (sources.size() < 5 && sources.stream().noneMatch(s -> s.getTitle().equals(source.getTitle()))) {
				sources.add(source);
			}
		}
	}

	ApiEndpoint copy() {
		ApiEndpoint copy = new ApiEndpoint();
		copy.origin = origin;
		copy.method = method;
		copy.id = id;
		copy.address = address;
		copy.host = host;
		copy.body = body;
		copy.dynamicUrl = dynamicUrl;
		copy.truncated = truncated;
		copy.occurrences = occurrences;
		copy.parameters = new ArrayList<>(parameters);
		copy.authHints = new ArrayList<>(authHints);
		copy.sources = new ArrayList<>(sources);
		return copy;
	}

	public String getId() {
		return id;
	}

	public Origin getOrigin() {
		return origin;
	}

	public String getMethod() {
		return method;
	}

	public String getAddress() {
		return address;
	}

	public String getHost() {
		return host;
	}

	public String getBody() {
		return body;
	}

	public boolean isDynamicUrl() {
		return dynamicUrl;
	}

	public boolean isTruncated() {
		return truncated;
	}

	public int getOccurrences() {
		return occurrences;
	}

	public List<Parameter> getParameters() {
		return Collections.unmodifiableList(parameters);
	}

	public List<AiEvidence> getSources() {
		return Collections.unmodifiableList(sources);
	}

	public List<String> getAuthHints() {
		return Collections.unmodifiableList(authHints);
	}

	public String parameterSummary() {
		return parameters.stream().map(Parameter::toString).collect(Collectors.joining("; "));
	}

	public String authSummary() {
		return authHints.isEmpty() ? AiText.str("api.auth_unknown") : String.join("; ", authHints);
	}

	public String detail() {
		return origin.label() + "\n" + (method.isEmpty() ? AiText.str("api.unknown") : method) + " "
				+ (address.isEmpty() ? AiText.str("api.runtime_url") : address) + "\n\n"
				+ AiText.str("api.host") + ": " + (host.isEmpty() ? AiText.str("api.base_unknown") : host) + "\n"
				+ (dynamicUrl ? AiText.str("api.dynamic_url") + "\n" : "")
				+ AiText.str("api.parameters") + ":\n" + (parameters.isEmpty()
						? AiText.str("api.unknown")
						: parameters.stream().map(Parameter::toString).collect(Collectors.joining("\n")))
				+ "\n\n"
				+ AiText.str("api.body") + ": " + (body.isEmpty() ? AiText.str("api.unknown") : body) + "\n"
				+ AiText.str("api.auth") + ": " + authSummary() + "\n\n" + AiText.str("api.boundary") + "\n"
				+ (truncated ? AiText.str("api.truncated") + "\n" : "")
				+ "\n" + String.format(AiText.str("analysis.occurrences"), occurrences) + "\n"
				+ "\n" + sources.stream().map(AiEvidence::getTitle).collect(Collectors.joining("\n"));
	}

	public AnalysisFinding asFinding() {
		AiEvidence source = sources.get(0);
		String code = detail();
		AnalysisFinding.Kind kind = origin == Origin.DECLARATION ? AnalysisFinding.Kind.HTTP_ROUTE
				: origin == Origin.ADDRESS ? AnalysisFinding.Kind.ENDPOINT : AnalysisFinding.Kind.REQUEST_BUILDER;
		return new AnalysisFinding(id, kind,
				method + ' ' + address, new AiEvidence(source.getNode(), id + " · " + source.getTitle(),
						code.length() > 4000 ? code.substring(0, 4000) : code, AiEvidence.fingerprint(code), code.length() > 4000));
	}
}
