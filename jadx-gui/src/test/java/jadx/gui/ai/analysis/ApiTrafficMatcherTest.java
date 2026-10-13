package jadx.gui.ai.analysis;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.gui.ai.AiEvidence;
import jadx.gui.burp.BurpExchange;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiTrafficMatcherTest {
	private ApiEndpoint endpoint(String method, String path) {
		return new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, method, path, "fixture.Api.load", null);
	}

	private BurpExchange request(String method, String host, String path) {
		return BurpExchange.parse(method + " " + path + " HTTP/1.1\r\nHost: " + host
				+ "\r\nAuthorization: Bearer PRIVATE_HEADER\r\nCookie: PRIVATE_COOKIE\r\n\r\n",
				"HTTP/1.1 200 OK\r\nSet-Cookie: PRIVATE_RESPONSE_COOKIE\r\n\r\nPRIVATE_RESPONSE_BODY");
	}

	@Test
	void matchesResolvedBasePathAndRejectsWrongHostMethodOrPort() {
		ApiEndpoint api = endpoint("GET", "users/{id}");
		ApiDossier dossier = new ApiDossier("A1");
		dossier.add(new ApiDossier.Binding("https://api.test:8443/v1/", "Factory", List.of(), null),
				new AiEvidence(null, "Factory", "", "", false));
		ApiTrafficMatcher.Match match = ApiTrafficMatcher.match(api, dossier, request("GET", "api.test:8443", "/v1/users/42"));
		assertThat(match.level()).isEqualTo(ApiTrafficMatcher.Level.FEATURES);
		assertThat(match.route()).isEqualTo("/v1/users/{id}");
		assertThat(ApiTrafficMatcher.match(api, dossier, request("GET", "other.test:8443", "/v1/users/42"))).isNull();
		assertThat(ApiTrafficMatcher.match(api, dossier, request("POST", "api.test:8443", "/v1/users/42"))).isNull();
		assertThat(ApiTrafficMatcher.match(api, dossier, request("GET", "api.test:443", "/v1/users/42"))).isNull();
		assertThat(ApiTrafficMatcher.match(api, dossier, request("GET", "api.test:8443", "/v1/users/42/extra"))).isNull();
	}

	@Test
	void unresolvedAuthorityAndUnknownMethodsStayCandidates() {
		ApiEndpoint relative = endpoint("GET", "/users/{id}");
		assertThat(ApiTrafficMatcher.match(relative, null, request("GET", "any.test", "/users/42")).level())
				.isEqualTo(ApiTrafficMatcher.Level.CANDIDATE);
		assertThat(ApiTrafficMatcher.match(endpoint("", "https://api.test/users/{id}"), null,
				request("DELETE", "api.test", "/users/42")).level()).isEqualTo(ApiTrafficMatcher.Level.CANDIDATE);
		relative.dynamicUrl();
		assertThat(ApiTrafficMatcher.match(relative, null, request("GET", "any.test", "/users/42"))).isNull();
	}

	@Test
	void routeSyntaxIsLiteralExceptForSingleSegmentParameters() {
		ApiEndpoint api = endpoint("GET", "https://api.test/v1.0/users/{id}");
		assertThat(ApiTrafficMatcher.match(api, null, request("GET", "api.test", "/v1X0/users/42"))).isNull();
		assertThat(ApiTrafficMatcher.match(api, null, request("GET", "api.test", "/v1.0/users/42?token=PRIVATE_QUERY"))).isNotNull();
		assertThat(ApiTrafficMatcher.match(endpoint("GET", "https://api.test/files/{id}.json"), null,
				request("GET", "api.test", "/files/42.json"))).isNotNull();
		assertThat(ApiTrafficMatcher.match(endpoint("GET", "https://api.test/{a}{b}{c}{d}/end"), null,
				request("GET", "api.test", "/" + "x".repeat(4000) + "/other"))).isNull();
	}

	@Test
	void projectRoundTripAndExportPersistMetadataWithoutRawSecretsOrPathValues() {
		ApiEndpoint api = endpoint("GET", "https://api.test/users/{id}");
		AnalysisReport report = new AnalysisReport();
		report.setFingerprint("fixture-fingerprint");
		report.addApi(api);
		report.setComplete(true);
		BurpExchange exchange = request("GET", "api.test", "/users/PRIVATE_USER_ID?token=PRIVATE_QUERY");
		ApiTrafficEvidence evidence = new ApiTrafficEvidence(api, exchange, URI.create("https://api.test"),
				ApiTrafficMatcher.match(api, null, exchange));
		report.putTrafficEvidence(api, evidence);
		report.putTrafficEvidence(api, evidence);
		assertThat(report.getTrafficEvidence()).hasSize(1);
		String json = defaultGsonBuilder().create().toJson(report);
		assertThat(json).doesNotContain("PRIVATE_", "rawRequest", "rawResponse").contains("authorization", "cookie", "token");
		AnalysisReport restored = defaultGsonBuilder().create().fromJson(json, AnalysisReport.class);
		assertThat(restored.getTrafficEvidence()).hasSize(1);
		assertThat(restored.getTrafficEvidence().get(0).belongsTo(restored.getApis().get(0))).isTrue();
		assertThat(restored.markdown()).doesNotContain("PRIVATE_").contains("/users/{id}");
		assertThat(restored.trafficEvidenceExport().get(0).get("endpointId")).isEqualTo(restored.getApis().get(0).getId());
		AnalysisReport differentInput = new AnalysisReport();
		differentInput.setFingerprint("other-input");
		differentInput.addApi(api.copy());
		differentInput.carryTrafficEvidence(report);
		assertThat(differentInput.getTrafficEvidence()).isEmpty();
		AnalysisReport rescan = new AnalysisReport();
		rescan.setFingerprint(report.getFingerprint());
		rescan.addApi(endpoint("GET", "/unrelated"));
		rescan.addApi(api.copy());
		rescan.carryTrafficEvidence(report);
		assertThat(rescan.getTrafficEvidence()).hasSize(1);
		assertThat(rescan.getTrafficEvidence().get(0).belongsTo(rescan.getApis().get(1))).isTrue();
		assertThatThrownBy(() -> report.putTrafficEvidence(api.copy(), evidence)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void evidenceLimitsAndSnapshotsDoNotShareMutableCollections() {
		ApiEndpoint api = endpoint("GET", "https://api.test/users/{id}");
		AnalysisReport report = new AnalysisReport();
		report.addApi(api);
		report.setComplete(true);
		AnalysisReport snapshot = report.snapshot();
		for (int i = 0; i < 20; i++) {
			BurpExchange e = request("GET", "api.test", "/users/" + i);
			report.putTrafficEvidence(api,
					new ApiTrafficEvidence(api, e, URI.create("https://api.test"), ApiTrafficMatcher.match(api, null, e)));
		}
		BurpExchange extra = request("GET", "api.test", "/users/21");
		assertThatThrownBy(() -> report.putTrafficEvidence(api,
				new ApiTrafficEvidence(api, extra, URI.create("https://api.test"), ApiTrafficMatcher.match(api, null, extra))))
						.isInstanceOf(IllegalStateException.class);
		assertThat(snapshot.getTrafficEvidence()).isEmpty();
		report.clearTrafficEvidence(api);
		assertThat(report.getTrafficEvidence()).isEmpty();
	}
}
