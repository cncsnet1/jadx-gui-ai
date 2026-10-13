package jadx.gui.ai.analysis;

import org.junit.jupiter.api.Test;

import jadx.gui.ai.AiCancellation;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRelationResolverTest {
	private ApiDossier analyze(String members) throws Exception {
		ApiDossier dossier = new ApiDossier("A1");
		ApiRelationResolver.analyze("package fixture; import retrofit2.Retrofit; import okhttp3.OkHttpClient; "
				+ "class Factory { " + members + " }", "fixture.Api", "fixture.Factory", null, dossier, new AiCancellation());
		return dossier;
	}

	@Test
	void keepsInstancesAndBuildSnapshotsSeparate() throws Exception {
		ApiDossier dossier = analyze("Api first() { var h = new OkHttpClient.Builder().addInterceptor(new Auth()).build(); "
				+ "var b = new Retrofit.Builder().baseUrl(\"https://one.test/\").client(h); var r = b.build(); "
				+ "b.baseUrl(\"https://two.test/\"); return r.create(Api.class); } "
				+ "Api second() { return new Retrofit.Builder().baseUrl(\"https://other.test/\").build().create(Api.class); }");
		assertThat(dossier.getBindings()).hasSize(2);
		assertThat(dossier.getBindings().get(0).getBaseUrl()).isEqualTo("https://one.test/");
		assertThat(dossier.getBindings().get(0).getInterceptors()).containsExactly("fixture.Auth");
		assertThat(dossier.getBindings().get(1).getBaseUrl()).isEqualTo("https://other.test/");
		assertThat(dossier.getBindings().get(1).getInterceptors()).isEmpty();
	}

	@Test
	void shadowedConstantsAndUnknownMutationsNeverBecomeHosts() throws Exception {
		ApiDossier dossier = analyze("static final String URL = \"https://wrong.test/\"; "
				+ "Api params(String URL) { return new Retrofit.Builder().baseUrl(URL).build().create(Api.class); } "
				+ "Api escaped() { var b = new Retrofit.Builder().baseUrl(URL); mutate((b)); return b.build().create(Api.class); } "
				+ "Api scope() { var b = new Retrofit.Builder().baseUrl(URL); b.unknownOption(); return b.build().create(Api.class); } "
				+ "class Nested { static final String URL = \"https://nested.test/\"; } ");
		assertThat(dossier.getBindings()).hasSize(3).allSatisfy(b -> assertThat(b.getBaseUrl()).isEmpty());
		assertThat(dossier.getGaps()).isNotEmpty();
	}

	@Test
	void rejectsBranchesAndUnrelatedTypes() throws Exception {
		assertThat(analyze("Api create(boolean flag) { if (flag) return new Retrofit.Builder().baseUrl(\"https://one.test/\")"
				+ ".build().create(Api.class); return null; }").getBindings()).isEmpty();
		assertThat(analyze("class Retrofit { class Builder {} } Api create() { return new Retrofit.Builder()"
				+ ".baseUrl(\"https://wrong.test/\").build().create(Api.class); }").getBindings()).isEmpty();
	}

	@Test
	void anonymousInterceptorsAreUnresolvedAndEvidenceIsRedacted() throws Exception {
		ApiDossier dossier = analyze("Api create() { var h = new OkHttpClient.Builder().addInterceptor(new Auth() {}).build(); "
				+ "return new Retrofit.Builder().baseUrl(\"https://user:fixture-password@example.test/\").client(h).build().create(Api.class); }");
		assertThat(dossier.getBindings()).hasSize(1);
		assertThat(dossier.getBindings().get(0).getInterceptors()).isEmpty();
		assertThat(dossier.describe()).doesNotContain("fixture-password");
		assertThat(dossier.getEvidence()).allSatisfy(e -> assertThat(e.getCode()).doesNotContain("fixture-password"));
	}
}
