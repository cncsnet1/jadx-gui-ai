package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;
import jadx.gui.ai.analysis.EvidenceTriage.Origin;
import jadx.gui.ai.analysis.EvidenceTriage.Signal;
import jadx.gui.ai.analysis.EvidenceTriage.Tier;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceTriageTest {

	@Test
	void namespacesAreBoundaryAwareHeuristicsAndUnknownCodeIsKept() {
		AnalysisReport report = report();
		AnalysisFinding app = finding("F1", Kind.CRYPTO, "com.example.app.Auth", "Ljavax/crypto/Cipher;.getInstance");
		AnalysisFinding neighboring = finding("F2", Kind.CRYPTO, "com.example.application.Auth", "Ljavax/crypto/Cipher;.getInstance");
		AnalysisFinding obfuscated = finding("F3", Kind.REQUEST_BUILDER, "a.b", "Lokhttp3/Request$Builder;.build");
		AnalysisFinding sdk = finding("F4", Kind.CRYPTO, "com.google.firebase.Auth", "Ljavax/crypto/Cipher;.getInstance");
		assertThat(EvidenceTriage.assess(report, app).getOrigin()).isEqualTo(Origin.APP_NAMESPACE);
		assertThat(EvidenceTriage.assess(report, app).getTier()).isEqualTo(Tier.PRIMARY);
		assertThat(EvidenceTriage.assess(report, neighboring).getOrigin()).isEqualTo(Origin.UNKNOWN);
		assertThat(EvidenceTriage.assess(report, obfuscated).getTier()).isEqualTo(Tier.SUPPORTING);
		assertThat(EvidenceTriage.assess(report, sdk).getTier()).isEqualTo(Tier.BACKGROUND);
		assertThat(EvidenceTriage.assess(report, sdk).getReasons()).contains("library_namespace_candidate", "call_reference");
		assertThat(EvidenceTriage.classRank(report, "com.example.app.Auth"))
				.isGreaterThan(EvidenceTriage.classRank(report, "a.b"));
		assertThat(EvidenceTriage.classRank(report, "a.b")).isGreaterThan(EvidenceTriage.classRank(report, "okhttp3.Client"));
		assertThat(EvidenceTriage.classRank(report, "io.flutter.Engine")).isLessThan(EvidenceTriage.classRank(report, "a.b"));
		assertThat(EvidenceTriage.assess(report, finding("F5", Kind.CRYPTO, "okhttp3evil.Client", "Cipher"))
				.getOrigin()).isEqualTo(Origin.UNKNOWN);
	}

	@Test
	void weakStringsAndRuntimeInventoryDoNotOutrankDeclarationsOrActualCalls() {
		AnalysisReport report = report();
		AnalysisFinding keyword = finding("F1", Kind.LOGIN, "com.example.app.Form", "const @0 = \"password\"");
		AnalysisFinding actual = finding("F2", Kind.LOGIN, "com.example.app.Form", "Lcom/example/app/Auth;.signIn");
		AnalysisFinding literal = finding("F3", Kind.ENDPOINT, "com.example.app.Config", "https://service.test/api?token=original-value");
		AnalysisFinding declaration = finding("F4", Kind.HTTP_ROUTE, "a.b", "@GET(\"/profile\")");
		AnalysisFinding runtime = new AnalysisFinding("K2000", Kind.APK_STRUCTURE, "runtime",
				resource("app.apk!/assets/uni-app-service.js:1:0", "runtime source"));
		assertThat(EvidenceTriage.assess(report, keyword).getReasons()).contains("login_keyword_only");
		assertThat(EvidenceTriage.assess(report, keyword).getTier()).isEqualTo(Tier.BACKGROUND);
		assertThat(EvidenceTriage.assess(report, actual).getSignal()).isEqualTo(Signal.CALL_REFERENCE);
		assertThat(EvidenceTriage.assess(report, literal).getTier()).isEqualTo(Tier.BACKGROUND);
		assertThat(EvidenceTriage.assess(report, runtime).getOrigin()).isEqualTo(Origin.FRAMEWORK_RUNTIME);
		List<AnalysisFinding> original = new ArrayList<>(List.of(runtime, keyword, literal, declaration, actual));
		List<AnalysisFinding> ranked = EvidenceTriage.ranked(report, AnalysisGoal.NETWORK, original);
		assertThat(ranked).containsExactlyInAnyOrderElementsOf(original);
		assertThat(ranked.subList(0, 2)).containsExactlyInAnyOrder(actual, declaration);
		assertThat(original.get(0)).isSameAs(runtime);
		assertThat(literal.getValue()).isEqualTo("https://service.test/api?token=original-value");
		AnalysisFinding credential = finding("F5", Kind.CREDENTIAL, "a.b", "password = 'original-password'");
		assertThat(EvidenceTriage.ranked(report, AnalysisGoal.SECRETS, List.of(declaration, credential))).first().isSameAs(credential);
	}

	@Test
	void mixedOwnershipUsesTheAppSourceAndExplicitSourceLocalityDoesNotInventEdges() {
		AnalysisReport report = report();
		AnalysisFinding mixed = finding("F1", Kind.HTTP_ROUTE, "retrofit2.Library", "@GET(\"/same\")");
		mixed.add(code("com.example.app.Api", "@GET(\"/same\")"), report);
		assertThat(EvidenceTriage.assess(report, mixed).getOrigin()).isEqualTo(Origin.APP_NAMESPACE);
		assertThat(EvidenceTriage.sameSource(code("com.example.app.Api", "one"), code("com.example.app.Api", "two"))).isTrue();
		assertThat(EvidenceTriage.sameSource(code("com.example.app.Api", "one"), code("com.example.app.Other", "two"))).isFalse();
		assertThat(EvidenceTriage.sameSource(resource("a.apk!/assets/app.js:1:12 · uni.request", "one"),
				resource("a.apk!/assets/app.js:20:1000", "two"))).isTrue();
		assertThat(EvidenceTriage.sameSource(resource("a.apk!/assets/app.js:1:0", "one"),
				resource("b.apk!/assets/app.js:1:0", "two"))).isFalse();
		assertThat(EvidenceTriage.sameSource(resource("Generic label", "one"), resource("Generic label", "two"))).isFalse();
		AnalysisFinding sdk = finding("F2", Kind.CREDENTIAL, "com.google.firebase.Config", "original-password");
		AiEvidence indirect = new AiEvidence(new JadxNodeRef(RefType.METHOD, "com.example.app.Loader", "read()V"),
				"com.example.app.Loader->read()V [resource reference]", "Resource filename reference in method: read", "indirect", false);
		sdk.add(indirect, report);
		assertThat(EvidenceTriage.assess(report, sdk).getOrigin()).isEqualTo(Origin.LIBRARY_NAMESPACE);
	}

	@Test
	void scannedSecretCandidatesRemainVisibleWithoutChangingSdkOrRuntimeAttribution() {
		String pem = "-----BEGIN PRIVATE KEY-----\nTUlJQlRlc3RGaXh0dXJl\n-----END PRIVATE KEY-----";
		String credential = "sdk-original-credential";
		String token = "Bearer original-token-value";
		for (String owner : List.of("com.google.firebase.Config", "io.dcloud.RuntimeConfig")) {
			AnalysisReport report = report();
			AiEvidence source = code(owner, pem + "\nclient_secret = \"" + credential + "\";\n" + token);
			new AnalysisRules(report).scan(source.getCode(), source.getTitle(), source.getNode());
			assertThat(report.getFindings()).extracting(AnalysisFinding::getKind)
					.containsExactlyInAnyOrder(Kind.PRIVATE_KEY, Kind.CREDENTIAL, Kind.TOKEN);
			assertThat(report.getFindings()).extracting(AnalysisFinding::getRawValue)
					.containsExactlyInAnyOrder(pem, credential, token);
			Origin expected = owner.startsWith("io.dcloud.") ? Origin.FRAMEWORK_RUNTIME : Origin.LIBRARY_NAMESPACE;
			for (AnalysisFinding finding : report.getFindings()) {
				EvidenceTriage.Decision decision = EvidenceTriage.assess(report, finding);
				assertThat(decision.getTier()).isEqualTo(Tier.SUPPORTING);
				assertThat(decision.getOrigin()).isEqualTo(expected);
				assertThat(decision.getReasons()).contains("secret_candidate_unverified",
						expected == Origin.FRAMEWORK_RUNTIME ? "framework_runtime_marker" : "library_namespace_candidate");
			}
			assertThat(EvidenceTriage.assess(report, finding("F4", Kind.LOGIN, owner, "password")).getTier())
					.isEqualTo(Tier.BACKGROUND);
			assertThat(EvidenceTriage.assess(report, finding("K1000", Kind.APK_STRUCTURE, owner, "runtime inventory")).getTier())
					.isEqualTo(Tier.BACKGROUND);
		}
	}

	@Test
	void bytecodeAndManagedLibrarySourcesRequireExactArchiveAndFileIdentity() {
		for (String path : List.of("assets/index.hbc", "assemblies/Application.dll")) {
			assertThat(EvidenceTriage.sameSource(resource("a.apk!/" + path + ":1:12 · inventory", "one"),
					resource("a.apk!/" + path + ":20:1000", "two"))).isTrue();
			assertThat(EvidenceTriage.sameSource(resource("a.apk!/" + path, "one"),
					resource("a.apk!/" + path + ":20:1000", "two"))).isTrue();
			assertThat(EvidenceTriage.sameSource(resource("a.apk!/" + path + ":1:12", "one"),
					resource("b.apk!/" + path + ":1:12", "two"))).isFalse();
			assertThat(EvidenceTriage.sameSource(resource("a.apk!/" + path + ":1:12", "one"),
					resource("a.apk!/other/" + path + ":1:12", "two"))).isFalse();
			assertThat(EvidenceTriage.sameSource(resource("a.apk!/" + path + ":1:12", "one"),
					resource("a.apk!/" + path + ".bak:1:12", "two"))).isFalse();
		}
	}

	@Test
	void manifestFallbackAndApplicationPackageSurviveSnapshotsAndOldReports() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.scan("<manifest package=\"com.embedded.library\"/>", "sample.apk!/assets/AndroidManifest.xml", null);
		assertThat(report.getAppPackage()).isEmpty();
		rules.scan(
				"<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n package=\"com.example.app\"><application/></manifest>",
				"sample.apk!/AndroidManifest.xml", null);
		assertThat(report.getAppPackage()).isEqualTo("com.example.app");
		rules.scan("<manifest package=\"com.other.app\"/>", "library.apk!/AndroidManifest.xml", null);
		assertThat(report.getAppPackage()).isEqualTo("com.example.app");
		AnalysisReport saved = new Gson().fromJson(new Gson().toJson(report.snapshot()), AnalysisReport.class);
		assertThat(saved.getAppPackage()).isEqualTo("com.example.app");
		assertThat(new Gson().fromJson("{}", AnalysisReport.class).getAppPackage()).isEmpty();
		saved.setAppPackage("bad/package");
		assertThat(saved.getAppPackage()).isEqualTo("com.example.app");
	}

	@Test
	void boundedPreviewsPromoteBusinessSourcesWithoutChangingIdentityOrLosingIndexCounts() {
		AnalysisReport report = report();
		AnalysisRules rules = new AnalysisRules(report);
		for (int i = 0; i < 140; i++) {
			AiEvidence source = code("com.google.firebase.Sdk" + i, "password = 'same-original-password'");
			rules.add(Kind.CREDENTIAL, "same-original-password", source.getTitle(), source.getNode(), source.getCode());
		}
		AnalysisFinding finding = report.getFindings().get(0);
		String reviewKey = finding.reviewKey();
		AiEvidence business = code("com.example.app.Auth", "password = 'same-original-password'");
		rules.add(Kind.CREDENTIAL, "same-original-password", business.getTitle(), business.getNode(), business.getCode());
		assertThat(finding.getEvidence()).hasSize(5);
		assertThat(finding.getEvidence().get(0).getNode().getDeclaringClass()).isEqualTo("com.example.app.Auth");
		assertThat(finding.getSourceReferences()).hasSize(128);
		assertThat(finding.getOmittedSourceReferences()).isEqualTo(13);
		assertThat(finding.getOccurrences()).isEqualTo(141);
		assertThat(finding.reviewKey()).isEqualTo(reviewKey);
		assertThat(finding.getRawValue()).isEqualTo("same-original-password");
		AnalysisFinding restored = new Gson().fromJson(new Gson().toJson(finding.copy()), AnalysisFinding.class);
		assertThat(restored.getSourceReferences()).hasSize(128);
		assertThat(restored.getOmittedSourceReferences()).isEqualTo(13);
		assertThat(restored.reviewKey()).isEqualTo(reviewKey);
	}

	@Test
	void reorderingAnOlderReportFreezesItsExistingReviewIdentity() {
		AnalysisFinding finding = finding("F1", Kind.CREDENTIAL, "com.google.firebase.A", "raw-value");
		JsonObject json = JsonParser.parseString(new Gson().toJson(finding)).getAsJsonObject();
		json.remove("reviewKey");
		json.remove("sourceReferences");
		AnalysisFinding old = new Gson().fromJson(json, AnalysisFinding.class);
		String reviewKey = old.reviewKey();
		old.add(code("com.example.app.Auth", "raw-value"), report());
		assertThat(old.reviewKey()).isEqualTo(reviewKey);
		assertThat(old.getSourceReferences()).hasSize(2);
		assertThat(old.getEvidence().get(0).getNode().getDeclaringClass()).isEqualTo("com.example.app.Auth");
	}

	private static AnalysisReport report() {
		AnalysisReport report = new AnalysisReport();
		report.setAppPackage("com.example.app");
		return report;
	}

	private static AnalysisFinding finding(String id, Kind kind, String cls, String text) {
		return new AnalysisFinding(id, kind, text, code(cls, text));
	}

	private static AiEvidence code(String cls, String text) {
		return new AiEvidence(new JadxNodeRef(RefType.METHOD, cls, "run()V"), cls + "->run()V", text, AiEvidence.fingerprint(text), false);
	}

	private static AiEvidence resource(String title, String text) {
		return new AiEvidence(null, title, text, AiEvidence.fingerprint(text), false);
	}
}
