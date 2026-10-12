package jadx.gui.ai.analysis;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jadx.api.data.impl.JadxNodeRef;
import jadx.api.plugins.input.data.annotations.AnnotationVisibility;
import jadx.api.plugins.input.data.annotations.EncodedType;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.api.plugins.input.data.annotations.JadxAnnotation;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiProfile;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;

class ApiExtractorTest {
	private static final JadxNodeRef REF = JadxNodeRef.forCls("Api");

	private static EncodedValue string(String value) {
		return new EncodedValue(EncodedType.ENCODED_STRING, value);
	}

	private static IAnnotation annotation(String type, Map<String, EncodedValue> values) {
		return new JadxAnnotation(AnnotationVisibility.RUNTIME, type, values);
	}

	private static IAnnotation annotation(String name, String value) {
		return annotation("Lretrofit2/http/" + name + ";", Map.of("value", string(value)));
	}

	private static AnnotationsAttr param(String name, String value) {
		return AnnotationsAttr.pack(List.of(annotation(name, value)));
	}

	@Test
	void extractsMethodPathNamedInputsAndHeaderCluesWithoutCredentialValues() {
		ApiEndpoint api = ApiExtractor.declaration(List.of(annotation("POST", "users/{id}?token=secret-query&sort=private-sort"),
				annotation("Headers", "Authorization: Bearer secret-header"), annotation("FormUrlEncoded", "")),
				List.of(param("Path", "id"), param("Query", "page"), param("Field", "email"), param("Header", "X-Api-Key")),
				List.of("int", "int", "java.lang.String", "java.lang.String"), "Api.login", REF);
		assertThat(api.getOrigin()).isEqualTo(ApiEndpoint.Origin.DECLARATION);
		assertThat(api.getMethod()).isEqualTo("POST");
		assertThat(api.getAddress()).isEqualTo("users/{id}");
		assertThat(api.getHost()).isEmpty();
		assertThat(api.getBody()).isEqualTo("FormUrlEncoded");
		assertThat(api.parameterSummary()).contains("Path · id", "Query · page", "Field · email", "Header · X-Api-Key",
				"Query (URL) · token");
		assertThat(api.authSummary()).contains("Bearer", "Authorization", "X-Api-Key");
		assertThat(ApiCatalogPanel.json(List.of(api))).doesNotContain("secret-query", "private-sort", "secret-header");
	}

	@Test
	void customHttpAndDynamicUrlDoNotFabricateABaseOrJsonSchema() {
		IAnnotation http = annotation("retrofit2.http.HTTP", Map.of("method", string("DELETE"), "hasBody",
				new EncodedValue(EncodedType.ENCODED_BOOLEAN, true)));
		ApiEndpoint api = ApiExtractor.declaration(List.of(http), List.of(param("Url", ""), param("Body", ""), param("HeaderMap", "")),
				List.of("java.lang.String", "example.Payload", "java.util.Map"), "Api.remove", REF);
		assertThat(api.getMethod()).isEqualTo("DELETE");
		assertThat(api.getAddress()).isEmpty();
		assertThat(api.getHost()).isEmpty();
		assertThat(api.isDynamicUrl()).isTrue();
		assertThat(api.getBody()).isEqualTo("Body: example.Payload");
		assertThat(api.getAuthHints()).isEmpty();
		assertThat(api.parameterSummary()).contains("HeaderMap");
	}

	@Test
	void legacyRetrofitAndMultipartArraysAreSupportedButUnrelatedAnnotationsAreIgnored() {
		IAnnotation get = annotation("retrofit/http/GET", Map.of("value", string("https://api.example.test/files")));
		IAnnotation headers = annotation("Headers", Map.of());
		assertThat(ApiExtractor.declaration(List.of(headers), List.of(), List.of(), "NoRoute", REF)).isNull();
		assertThat(ApiExtractor.declaration(List.of(annotation("my.http.GET", Map.of("value", string("/fake")))),
				List.of(), List.of(), "Unrelated", REF)).isNull();
		headers = annotation("retrofit2.http.Headers", Map.of("value", new EncodedValue(EncodedType.ENCODED_ARRAY,
				List.of(string("Authorization: Basic c2VjcmV0"), string("Cookie: private-cookie")))));
		ApiEndpoint api = ApiExtractor.declaration(List.of(get, headers, annotation("Multipart", "")),
				List.of(param("Part", "photo"), param("PartMap", "")), List.of("RequestBody", "Map"), "Api.files", REF);
		assertThat(api.getHost()).isEqualTo("api.example.test");
		assertThat(api.getBody()).isEqualTo("Multipart");
		assertThat(api.getAuthHints()).contains("Basic [redacted]");
		assertThat(ApiCatalogPanel.json(List.of(api))).doesNotContain("c2VjcmV0", "private-cookie");
	}

	@Test
	void addressLeadsKeepQueryNamesButDoNotInventHttpMethods() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.scan("https://bob:pass@api.example.test/v1?token=secret&user_id=123#private-fragment", "config.txt", null);
		ApiEndpoint api = report.getApis().get(0);
		assertThat(api.getOrigin()).isEqualTo(ApiEndpoint.Origin.ADDRESS);
		assertThat(api.getMethod()).isEmpty();
		assertThat(api.getAddress()).isEqualTo("https://api.example.test/v1");
		assertThat(api.parameterSummary()).contains("token", "user_id");
		assertThat(ApiCatalogPanel.json(report.getApis())).doesNotContain("bob", "pass", "secret", "123", "private-fragment");
		assertThat(api.getAuthHints()).contains("URL userinfo [redacted]");
	}

	@Test
	void networkCallsAreEntryPointsWithoutGuessingWhichConstantsTheyConsume() {
		ApiEndpoint api = ApiExtractor.networkContext("Lokhttp3/Request$Builder;.url\nLokhttp3/Request$Builder;.post", "Client.send", REF);
		assertThat(api.getOrigin()).isEqualTo(ApiEndpoint.Origin.CODE_CONTEXT);
		assertThat(api.asFinding().getKind()).isEqualTo(AnalysisFinding.Kind.REQUEST_BUILDER);
		assertThat(api.getAddress()).isEmpty();
		assertThat(api.getMethod()).isEmpty();
		assertThat(ApiExtractor.networkContext("Lexample/Request$Builder;.post", "NotOkHttp", REF)).isNull();
	}

	@Test
	void inventoryDeduplicatesAndPersistsIndependentSnapshotsWithSeparateDeclarationBudget() {
		AnalysisReport report = new AnalysisReport();
		ApiEndpoint first = new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "", "https://api.example.test/a?key=secret", "One", null);
		report.addApi(first);
		AnalysisReport snapshot = report.snapshot();
		report.addApi(new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "", "https://api.example.test/a?key=other-secret", "Two", null));
		assertThat(report.getApis()).hasSize(1);
		assertThat(report.getApis().get(0).getSources()).hasSize(2);
		assertThat(snapshot.getApis().get(0).getSources()).hasSize(1);
		for (int i = 0; i < 800; i++) {
			report.addApi(new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "", "https://api.example.test/" + i, "Source", null));
		}
		report.addApi(new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "GET", "/users", "Api.users", REF));
		assertThat(report.getApis()).hasSize(751);
		assertThat(report.getGaps()).isNotEmpty();
		var gson = defaultGsonBuilder().create();
		String json = gson.toJson(report);
		assertThat(json).doesNotContain("other-secret", "key=secret");
		assertThat(gson.fromJson(json, AnalysisReport.class).getApis()).hasSize(751);
		assertThat(gson.fromJson("{}", AnalysisReport.class).getApis()).isEmpty();
	}

	@Test
	void apiOnlyReportsReachTheModelWithStructuredMetadata() throws Exception {
		AnalysisReport report = new AnalysisReport();
		ApiEndpoint api = ApiExtractor.declaration(List.of(annotation("GET", "/users")),
				List.of(param("Query", "page")), List.of("int"), "Api.users", null);
		report.addApi(api);
		assertThat(report.hasEvidence()).isTrue();
		java.util.List<String> requests = new java.util.ArrayList<>();
		var investigator = new AnalysisInvestigator(null, (model, messages, temperature, tokens, cancellation, output) -> {
			requests.add(messages.toString());
			return requests.size() == 1 ? "{\"inspect\":[\"A1\",\"A999\"]}" : "[S1]";
		});
		var result = investigator.investigate(report, AnalysisGoal.NETWORK, "list APIs", null, new AiProfile(), new AiCancellation(), s -> {
		});
		assertThat(requests).hasSize(2);
		assertThat(requests.get(0)).contains("A1", "/users");
		assertThat(result.getEvidence().get(0).getCode()).contains("Query · page");
	}
}
