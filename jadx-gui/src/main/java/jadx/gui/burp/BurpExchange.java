package jadx.gui.burp;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Session-only HTTP text. Never put this object in a saved project or an AI prompt. */
public final class BurpExchange {
	private final String request;
	private final String response;
	private final String method;
	private final String protocol;
	private final URI target;
	private final Map<String, String> headers;
	private final String body;
	private final boolean completeHeaders;
	private final boolean truncated;
	private final int status;
	private final long collectedAt = System.currentTimeMillis();

	private BurpExchange(String request, String response, String method, String protocol, URI target,
			Map<String, String> headers, String body, boolean completeHeaders, boolean truncated, int status) {
		this.request = request;
		this.response = response;
		this.method = method;
		this.protocol = protocol;
		this.target = target;
		this.headers = headers;
		this.body = body;
		this.completeHeaders = completeHeaders;
		this.truncated = truncated;
		this.status = status;
	}

	public static BurpExchange parse(String request, String response) {
		if (request == null || request.length() > 32_000 || response == null || response.length() > 64_000
				|| request.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("Unsupported HTTP record");
		}
		String normalized = request.replace("\r\n", "\n");
		int separator = normalized.indexOf("\n\n");
		String prelude = separator < 0 ? normalized : normalized.substring(0, separator);
		String body = separator < 0 ? "" : normalized.substring(separator + 2);
		String[] lines = prelude.split("\n", -1);
		String[] first = lines[0].split(" ", -1);
		if (first.length != 3 || !first[0].matches("[A-Z][A-Z0-9_-]{0,23}")
				|| !first[2].matches("HTTP/(1\\.[01]|2(?:\\.0)?)")) {
			throw new IllegalArgumentException("Unsupported HTTP request line");
		}
		Map<String, String> headers = new LinkedHashMap<>();
		for (int i = 1; i < lines.length; i++) {
			int colon = lines[i].indexOf(':');
			if (colon <= 0 || !lines[i].substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
				// Truncated headers can be displayed, but never forwarded as a complete request.
				if (request.contains("... (truncated)")) {
					break;
				}
				throw new IllegalArgumentException("Unsupported HTTP header");
			}
			String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
			String value = lines[i].substring(colon + 1).trim();
			if (headers.putIfAbsent(name, value) != null && (name.equals("host") || name.equals("content-length"))) {
				throw new IllegalArgumentException("Ambiguous HTTP framing");
			}
		}
		URI target;
		if (first[1].startsWith("http://") || first[1].startsWith("https://")) {
			target = URI.create(first[1]);
		} else {
			if (!first[1].startsWith("/") || first[1].startsWith("//") || !headers.containsKey("host")) {
				throw new IllegalArgumentException("Missing request authority");
			}
			target = URI.create("//" + headers.get("host") + first[1]);
		}
		if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
				|| target.getPort() > 65535 || target.getPort() == 0) {
			throw new IllegalArgumentException("Invalid request authority");
		}
		if (headers.containsKey("host")) {
			URI headerHost = URI.create("//" + headers.get("host"));
			if (!target.getHost().equalsIgnoreCase(headerHost.getHost()) || target.getPort() != headerHost.getPort()
					|| headerHost.getUserInfo() != null || (headerHost.getRawPath() != null && !headerHost.getRawPath().isEmpty())) {
				throw new IllegalArgumentException("Conflicting request authority");
			}
		}
		int status = 0;
		java.util.regex.Matcher responseLine =
				java.util.regex.Pattern.compile("^HTTP/[0-9.]+ ([1-5][0-9]{2})(?:[ \\r\\n]|$)").matcher(response);
		if (responseLine.find()) {
			status = Integer.parseInt(responseLine.group(1));
		}
		return new BurpExchange(request, response, first[0], first[2], target, headers, body, separator >= 0,
				request.contains("... (truncated)"), status);
	}

	public String method() {
		return method;
	}

	public String host() {
		return target.getHost().toLowerCase(Locale.ROOT);
	}

	public int port() {
		return target.getPort();
	}

	public String scheme() {
		return target.getScheme() == null ? "" : target.getScheme();
	}

	public String path() {
		return target.getRawPath().isEmpty() ? "/" : target.getRawPath();
	}

	public int status() {
		return status;
	}

	public long collectedAt() {
		return collectedAt;
	}

	public boolean truncated() {
		return truncated || response.contains("... (truncated)");
	}

	public String rawRequest() {
		return request;
	}

	public String rawResponse() {
		return response;
	}

	public List<String> headerNames() {
		return safeNames(new ArrayList<>(headers.keySet()));
	}

	public List<String> queryNames() {
		String query = target.getRawQuery();
		if (query == null) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		for (String part : query.split("&", 81)) {
			names.add(part.split("=", 2)[0]);
		}
		return safeNames(names);
	}

	public static List<String> safeNames(List<String> values) {
		return values.stream().limit(80).map(s -> s.matches("[A-Za-z0-9_.%\\[\\]-]{1,64}") ? s : "[omitted]")
				.distinct().collect(java.util.stream.Collectors.toList());
	}

	/** Text snapshots cannot reliably reproduce binary, HTTP/2, chunked, or truncated messages. */
	public boolean canStage() {
		if (truncated || !completeHeaders || !protocol.startsWith("HTTP/1.") || headers.containsKey("transfer-encoding")
				|| request.length() > 24_000 || request.chars().anyMatch(c -> c > 127 || (c < 32 && c != 9 && c != 10 && c != 13))) {
			return false;
		}
		String length = headers.get("content-length");
		if (length == null) {
			return body.isEmpty();
		}
		try {
			// Count the original body so CRLF inside a body does not get normalized away.
			int separator = request.indexOf("\r\n\r\n");
			int size = separator < 0 ? body.length() : request.length() - separator - 4;
			return length.matches("[0-9]{1,8}") && Integer.parseInt(length) == size;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
