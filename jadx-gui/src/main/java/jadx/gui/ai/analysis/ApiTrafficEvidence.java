package jadx.gui.ai.analysis;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jadx.gui.ai.AiEvidence;
import jadx.gui.burp.BurpExchange;
import jadx.gui.burp.BurpText;

/** Persisted, bounded HTTP observations including original request and response values. */
public final class ApiTrafficEvidence {
	private String endpointKey;
	private String sampleKey;
	private String origin;
	private String route;
	private String method;
	private int status;
	private long collectedAt;
	private ApiTrafficMatcher.Level level;
	private boolean truncated;
	private List<String> queryNames;
	private List<String> headerNames;
	private String rawRequest = "";
	private String rawResponse = "";

	private ApiTrafficEvidence() {
	}

	public ApiTrafficEvidence(ApiEndpoint endpoint, BurpExchange exchange, URI scope, ApiTrafficMatcher.Match match) {
		endpointKey = key(endpoint);
		sampleKey = AiEvidence.fingerprint(exchange.rawRequest() + '\0' + exchange.rawResponse());
		origin = scope.toString();
		route = ApiEndpoint.boundedText(match.route(), 2000);
		method = exchange.method();
		status = exchange.status();
		collectedAt = exchange.collectedAt();
		level = match.level();
		truncated = exchange.truncated();
		queryNames = List.copyOf(exchange.queryNames());
		headerNames = List.copyOf(exchange.headerNames());
		rawRequest = ApiEndpoint.boundedText(exchange.rawRequest(), 32_000);
		rawResponse = ApiEndpoint.boundedText(exchange.rawResponse(), 64_000);
		truncated |= exchange.rawRequest().length() > rawRequest.length() || exchange.rawResponse().length() > rawResponse.length();
	}

	public String getRawRequest() {
		return rawRequest;
	}

	public String getRawResponse() {
		return rawResponse;
	}

	static String key(ApiEndpoint endpoint) {
		return AiEvidence.fingerprint(endpoint.identity() + '\n' + endpoint.isDynamicUrl());
	}

	public boolean belongsTo(ApiEndpoint endpoint) {
		return key(endpoint).equals(endpointKey);
	}

	String endpointKey() {
		return endpointKey;
	}

	public String identity() {
		return endpointKey + ':' + sampleKey + ':' + origin;
	}

	public String describe() {
		return level.label() + " · " + method + " " + route + '\n' + BurpText.str("origin") + ": " + origin + '\n'
				+ BurpText.str("status") + ": " + (status == 0 ? "—" : status) + '\n'
				+ BurpText.str("query_names") + ": " + String.join(", ", queryNames) + '\n'
				+ BurpText.str("header_names") + ": " + String.join(", ", headerNames) + '\n'
				+ BurpText.str("collected") + ": " + Instant.ofEpochMilli(collectedAt) + '\n'
				+ (truncated ? BurpText.str("truncated") + '\n' : "") + BurpText.str("evidence_boundary")
				+ (rawRequest.isEmpty() ? "" : "\n\nHTTP request:\n" + rawRequest)
				+ (rawResponse.isEmpty() ? "" : "\n\nHTTP response:\n" + rawResponse);
	}
}
