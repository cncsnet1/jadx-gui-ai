package jadx.gui.ai.analysis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiProfile;

import static org.assertj.core.api.Assertions.assertThat;

class ApiScannerIntegrationTest {
	@TempDir
	Path temporary;

	@Test
	void classFileAnnotationsReachInventoryAndAiWithParameterTypes() throws Exception {
		Path classes = temporary.resolve("classes");
		Files.createDirectories(classes);
		Path annotations = temporary.resolve("retrofit2/http");
		Files.createDirectories(annotations);
		List<String> arguments = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", classes.toString()));
		for (Map.Entry<String, String> entry : Map.of(
				"GET", "String value() default \"\";", "Query", "String value();", "Path", "String value();",
				"Headers", "String[] value();", "Url", "", "Body", "",
				"HTTP", "String method(); String path() default \"\"; boolean hasBody() default false;").entrySet()) {
			Path source = annotations.resolve(entry.getKey() + ".java");
			Files.writeString(source, "package retrofit2.http; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) "
					+ "@Target({ElementType.METHOD, ElementType.PARAMETER}) public @interface " + entry.getKey() + " { " + entry.getValue()
					+ " }");
			arguments.add(source.toString());
		}
		Path source = temporary.resolve("Api.java");
		Files.writeString(source, "import retrofit2.http.*; public interface Api { "
				+ "@GET(\"users/{id}?token=fixture-query-secret\") @Headers({\"Authorization: Bearer fixture-header-secret\"}) "
				+ "String users(@Path(\"id\") int id, @Query(\"page\") int page); "
				+ "@HTTP(method=\"DELETE\", hasBody=true) String remove(@Url String url, @Body Object body); }", StandardCharsets.UTF_8);
		arguments.add(source.toString());
		Path retrofit = temporary.resolve("retrofit2/Retrofit.java");
		Files.writeString(retrofit, "package retrofit2; public class Retrofit { public <T> T create(Class<T> type) { return null; } "
				+ "public static class Builder { public Builder baseUrl(String value) { return this; } public Retrofit build() { return new Retrofit(); } } }");
		Path factory = temporary.resolve("Factory.java");
		Files.writeString(factory, "import retrofit2.Retrofit; public class Factory { public Api first() { return new Retrofit.Builder()"
				+ ".baseUrl(\"https://api.example.test/\").build().create(Api.class); } "
				+ "public String users(Api api) { return api.users(1, 2); } public String screen(Api api) { return users(api); } }");
		arguments.add(retrofit.toString());
		arguments.add(factory.toString());
		assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new))).isZero();
		JadxArgs args = new JadxArgs();
		try (var files = Files.walk(classes)) {
			args.setInputFiles(
					files.filter(p -> p.toString().endsWith(".class")).map(Path::toFile).collect(java.util.stream.Collectors.toList()));
		}
		args.setInlineMethods(false);
		args.setUseDxInput(false);
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), r -> {
			});
			assertThat(report.getGaps()).isEmpty();
			assertThat(report.getApis()).filteredOn(e -> e.getOrigin() == ApiEndpoint.Origin.DECLARATION).hasSize(2);
			ApiEndpoint users = report.getApis().stream().filter(e -> e.getMethod().equals("GET")).findFirst().orElseThrow();
			assertThat(users.getAddress()).isEqualTo("users/{id}");
			assertThat(users.parameterSummary()).contains("Path · id : int", "Query · page : int");
			assertThat(users.getAuthHints()).contains("Bearer [redacted]");
			ApiDossier dossier = new ApiRelationResolver(decompiler).resolve(users, new AiCancellation());
			assertThat(dossier.getBindings()).hasSize(1);
			assertThat(dossier.getBindings().get(0).getBaseUrl()).isEqualTo("https://api.example.test/");
			assertThat(dossier.getEvidence()).anyMatch(e -> e.getCode().contains("Api.class"));
			assertThat(dossier.getCallPath().getEdges()).hasSize(2);
			assertThat(dossier.getCallPath().getNodes()).extracting(n -> n.getMethod().getShortId())
					.contains("users(II)Ljava/lang/String;", "screen(LApi;)Ljava/lang/String;");
			report.putApiDossier(dossier);
			assertThat(report.markdown()).contains("https://api.example.test/");
			ApiEndpoint remove = report.getApis().stream().filter(e -> e.getMethod().equals("DELETE")).findFirst().orElseThrow();
			assertThat(remove.isDynamicUrl()).isTrue();
			assertThat(remove.getBody()).contains("java.lang.Object");
			assertThat(report.markdown()).doesNotContain("fixture-query-secret", "fixture-header-secret");
			List<String> requests = new ArrayList<>();
			var investigator = new AnalysisInvestigator(decompiler, (model, messages, temperature, tokens, cancellation, output) -> {
				requests.add(messages.toString());
				return requests.size() == 1 ? "{\"inspect\":[\"" + users.getId() + "\"]}" : "Observed declaration [S1]";
			});
			var result = investigator.investigate(report, AnalysisGoal.NETWORK, "API inventory", null, new AiProfile(),
					new AiCancellation(), s -> {
					});
			assertThat(requests).hasSize(2);
			assertThat(requests.toString()).doesNotContain("fixture-query-secret", "fixture-header-secret");
			assertThat(result.getEvidence()).anyMatch(e -> e.getTitle().contains("definition"));
			assertThat(result.getEvidence()).filteredOn(e -> e.getTitle().contains("definition"))
					.allSatisfy(e -> assertThat(e.getCode()).contains("users("));
			var collected = investigator.collectContext(List.of(users.asFinding()), false, new AiCancellation(), ignored -> {
			});
			assertThat(collected.codeSources).isNotEmpty();
			for (int i = 0; i < collected.evidence.size(); i++) {
				var node = collected.evidence.get(i).getNode();
				if (node != null && node.getDeclaringClass().equals("Api")) {
					assertThat(collected.codeSources).as("Abstract signatures do not establish reachable behavior")
							.doesNotContain("S" + (i + 1));
				}
			}
			var run = new InvestigationRun("fixture", InvestigationService.profile(decompiler), AnalysisGoal.NETWORK, "", users.getId());
			run.select(List.of(users.asFinding()));
			String observation =
					new LocalAnalysisTools(decompiler).execute("inspect_api", users.getId(), "", run, report, new AiCancellation());
			assertThat(observation).contains("P1 = N1", "INVOKE_INTERFACE", "INVOKE_VIRTUAL");
			assertThat(run.getNodes().values()).anyMatch(n -> n.getShortId().equals("screen(LApi;)Ljava/lang/String;"));
			assertThat(run.getEvidence().entries()).allSatisfy(e -> assertThat(e.isBehavioralCode()).isFalse());
		}
	}
}
