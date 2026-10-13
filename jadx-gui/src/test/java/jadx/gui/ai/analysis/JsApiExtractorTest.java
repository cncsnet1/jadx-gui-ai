package jadx.gui.ai.analysis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsApiExtractorTest {

	@Test
	void extractsUniRequestAndRetainsActualLiteralValues() {
		ApiEndpoint api = only("uni.request({url:'/v1/login?token=private-query',method:'POST',"
				+ "data:{username:'private-name',password:secret},header:{Authorization:'Bearer private-header'}});");
		assertThat(api.getMethod()).isEqualTo("POST");
		assertThat(api.getAddress()).isEqualTo("/v1/login?token=private-query");
		assertThat(api.getHost()).isEmpty();
		assertThat(api.parameterSummary()).contains("Data (JS) · username", "Data (JS) · password", "Header (JS) · Authorization");
		assertThat(ApiCatalogPanel.json(List.of(api))).contains("private-query", "private-name", "private-header");
		assertThat(api.getParameters()).filteredOn(value -> value.getName().equals("password"))
				.allSatisfy(value -> assertThat(value.getValue()).isEmpty());
		assertThat(api.getSources().get(0).getCode()).contains("password:secret", "Bearer private-header");
		assertThat(api.getSources().get(0).getTitle()).startsWith("assets/app.js:1:0 · uni.request");
	}

	@Test
	void extractsFetchAndJsonObjectKeysWithNestedValues() {
		ApiEndpoint api = only("fetch('https://api.example.test/upload', {method:'PUT', headers:{'X-Api-Key':key},"
				+ "body:JSON.stringify({file:transform({nested:1}), meta:{size:1}})});");
		assertThat(api.getHost()).isEqualTo("api.example.test");
		assertThat(api.getMethod()).isEqualTo("PUT");
		assertThat(api.parameterSummary()).contains("Body (JS) · file", "Body (JS) · meta", "Header (JS) · X-Api-Key")
				.doesNotContain("nested", "size");
		assertThat(api.getBody()).isEqualTo("JSON.stringify(object literal)");
	}

	@Test
	void handlesAxiosOverloadsWithoutInventingAnInstanceBaseUrl() {
		List<ApiEndpoint> apis = JsApiExtractor.scan("axios.get('/users',{params:{page:1}});\n"
				+ "axios.post('/users',{name:user},{headers:{Cookie:cookie}});\n"
				+ "axios({url:'/users',method:'DELETE',data:{id:12}});", "assets/app.js");
		assertThat(apis).hasSize(3);
		assertThat(apis.get(0).parameterSummary()).contains("Query (JS) · page");
		assertThat(apis.get(1).parameterSummary()).contains("Data (JS) · name", "Header (JS) · Cookie");
		assertThat(apis.get(2).getMethod()).isEqualTo("DELETE");
		assertThat(apis).allSatisfy(api -> assertThat(api.getHost()).isEmpty());
	}

	@Test
	void leavesVariableConcatenationTemplatesAndSpreadUnknown() {
		for (String call : List.of("fetch(base+'/users')", "fetch(`/users/${id}`)",
				"uni.request({url:'/known',...options})", "axios({url:'/known',['url']:other})")) {
			ApiEndpoint api = only(call);
			assertThat(api.getAddress()).isEmpty();
			assertThat(api.isDynamicUrl()).isTrue();
		}
		assertThat(only("fetch('/known', options)").getMethod()).isEmpty();
		assertThat(only("axios(options)").getMethod()).isEmpty();
		assertThat(only("uni.request({url:'/known',method:verb})").getMethod()).isEmpty();
		assertThat(only("fetch('/known')").getMethod()).isEqualTo("GET");
	}

	@Test
	void ignoresCommentsStringsRegexAndDifferentReceivers() {
		assertThat(JsApiExtractor.scan("// fetch('/comment')\n/*axios.get('/comment')*/\n"
				+ "const sample=\"uni.request({url:'/string'})\"; const re=/fetch\\('/fake'\\)/;"
				+ "other.fetch('/other'); axiosOther('/other'); function fetch(value) {}", "app.js")).isEmpty();
	}

	@Test
	void keepsLastLiteralPropertyAndReportsIncompleteCoverageAtBoundaries() {
		assertThat(only("uni.request({url:'/old',url:'/new',method:'GET',method:'POST'})").getAddress()).isEqualTo("/new");
		List<ApiEndpoint> apis = JsApiExtractor.scan("fetch('/ok');".repeat(300), "app.js");
		assertThat(apis).hasSize(250);
		assertThat(apis.get(249).isTruncated()).isTrue();
		assertThat(JsApiExtractor.scan("fetch('/unterminated", "app.js")).isEmpty();
	}

	@Test
	void scansOnlyInlineScriptsInHtmlAndRetainsResourceLines() {
		List<ApiEndpoint> apis = JsApiExtractor.scan("<p>fetch('/example')</p>\n<script>\nfetch('/real');\n</script>"
				+ "<!--<script>fetch('/comment');</script>-->", "assets/index.html");
		assertThat(apis).hasSize(1);
		assertThat(apis.get(0).getAddress()).isEqualTo("/real");
		assertThat(apis.get(0).getSources().get(0).getTitle()).startsWith("assets/index.html:3:");
		assertThat(JsApiExtractor.scan("fetch('/partial')", "app.js", true).get(0).isTruncated()).isTrue();
	}

	@Test
	void arithmeticDivisionDoesNotConsumeFollowingRequestsWhileRegexContentsStayIgnored() {
		List<ApiEndpoint> apis = JsApiExtractor.scan("const ratio=a/b; fetch('/api');"
				+ "let fraction=(a+b)/2; axios.get('/second');"
				+ "a++/2; fetch('/post-increment'); counters[i]--/2; fetch('/post-decrement');"
				+ "const re=/[/]fetch('fake')/; if (ratio) /fetch('also-fake')/.test(value);", "app.js");
		assertThat(apis).extracting(ApiEndpoint::getAddress).containsExactly("/api", "/second", "/post-increment", "/post-decrement");
	}

	@Test
	void truncatedHtmlRetainsRequestsFromAnOpenedScriptWithoutItsClosingTag() {
		List<ApiEndpoint> apis = JsApiExtractor.scan("<html>\n<script>fetch('/before-limit');\nconst pending = ", "index.html", true);
		assertThat(apis).hasSize(1);
		assertThat(apis.get(0).getAddress()).isEqualTo("/before-limit");
		assertThat(apis.get(0).isTruncated()).isTrue();
		assertThat(apis.get(0).getSources().get(0).getTitle()).startsWith("index.html:2:");
	}

	@Test
	void resolvesOnlyProvenLiteralConcatenationsAndPriorSameScopeConstantsWithTheirSource() {
		ApiEndpoint api = only("function request() { const BASE='https://'+'api.example.test'; "
				+ "uni.request({url:BASE+'/login',method:'POST',data:{count:12,flag:true,missing:null,neg:-2,token:'actual-token'}}); }");
		assertThat(api.getAddress()).isEqualTo("https://api.example.test/login");
		assertThat(api.getSources()).hasSize(2);
		assertThat(api.getSources().get(1).getCode()).contains("const BASE='https://'+'api.example.test'");
		assertThat(api.getParameters()).extracting(ApiEndpoint.Parameter::getValue)
				.contains("12", "true", "null", "-2", "actual-token");
		assertThat(only("fetch('https://'+'api.example.test'+'/literal')").getAddress())
				.isEqualTo("https://api.example.test/literal");
	}

	@Test
	void doesNotGuessImportsClosuresMutableConstantsOrShadowedBindings() {
		for (String source : List.of("let BASE='/before'; BASE=runtime; fetch(BASE+'/path')",
				"const BASE='/outer'; function inner(BASE) { fetch(BASE+'/path'); }",
				"function request() { fetch(BASE+'/path'); } const BASE='/later';",
				"fetch(BASE+'/path'); const BASE='/later';",
				"const BASE='/before'; BASE='/after'; fetch(BASE+'/path');",
				"import BASE from './config'; fetch(BASE+'/path')")) {
			assertThat(only(source).getAddress()).as(source).isEmpty();
		}
	}

	private static ApiEndpoint only(String source) {
		List<ApiEndpoint> endpoints = JsApiExtractor.scan(source, "assets/app.js");
		assertThat(endpoints).hasSize(1);
		return endpoints.get(0);
	}
}
