package jadx.gui.ai.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;
import jadx.gui.ai.fixtures.AnalysisFixture;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisBudgetTest {
	@TempDir
	Path temporary;

	@Test
	void resourceFloodDoesNotConsumeTheReservedSpaceForLaterApplicationBytecode() throws Exception {
		Path manifest = Files.writeString(temporary.resolve("AndroidManifest.xml"),
				"<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"jadx.gui.ai.fixtures\"><application/></manifest>");
		StringBuilder json = new StringBuilder("{");
		for (int i = 0; i < 200; i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append("\n\"password_").append(i).append("\":\"sdk-original-").append(i).append('"');
		}
		Path resource = Files.writeString(temporary.resolve("sdk.json"), json.append("\n}").toString());
		JadxArgs args = new JadxArgs();
		args.setUseDxInput(false);
		args.setInputFiles(List.of(manifest.toFile(), resource.toFile(),
				Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile()));
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("budget", new AiCancellation(), ignored -> {
			});
			assertThat(report.getAppPackage()).isEqualTo("jadx.gui.ai.fixtures");
			assertThat(report.getFindings()).filteredOn(finding -> finding.getKind() == Kind.CREDENTIAL)
					.extracting(AnalysisFinding::getValue).contains("sdk-original-0", AnalysisFixture.PASSWORD).hasSize(151);
			assertThat(report.getBudgetOmissions()).containsEntry("finding.general.credential", 50);
			assertThat(report.getFindings()).filteredOn(finding -> finding.getValue().equals(AnalysisFixture.PASSWORD))
					.allSatisfy(finding -> assertThat(EvidenceTriage.assess(report, finding).getOrigin())
							.isEqualTo(EvidenceTriage.Origin.APP_NAMESPACE));
		}
	}

	@Test
	void findingReserveIsBoundedAndExistingEvidenceIsNeverReplaced() {
		AnalysisReport report = report();
		AnalysisRules rules = new AnalysisRules(report);
		for (int i = 0; i < 151; i++) {
			rules.add(Kind.CREDENTIAL, "sdk-original-" + i, "assets/sdk.json:" + i, null, "password='sdk-original-" + i + "'");
		}
		AnalysisFinding first = report.getFindings().get(0);
		rules.add(Kind.CREDENTIAL, "sdk-code-original", "com.google.firebase.Config", node("com.google.firebase.Config"),
				"sdk-code-original");
		for (int i = 0; i < 51; i++) {
			JadxNodeRef ref = node(i % 2 == 0 ? "com.example.app.Auth" : "a.b");
			rules.add(Kind.CREDENTIAL, "business-original-" + i, ref + ":" + i, ref, "password='business-original-" + i + "'");
		}
		assertThat(report.getFindings()).hasSize(200).contains(first);
		assertThat(report.getFindings().get(0)).isSameAs(first);
		assertThat(report.getFindings()).extracting(AnalysisFinding::getValue).contains("business-original-49");
		assertThat(report.getBudgetOmissions()).containsEntry("finding.general.credential", 2)
				.containsEntry("finding.code_reserve.credential", 1);
		assertThat(report.getBudgetOmittedCount()).isEqualTo(3);
		AnalysisReport saved = new Gson().fromJson(new Gson().toJson(report.snapshot()), AnalysisReport.class);
		assertThat(saved.getBudgetOmissions()).isEqualTo(report.getBudgetOmissions());
		assertThat(saved.coverage()).contains("3");
		assertThat(new Gson().fromJson("{}", AnalysisReport.class).getBudgetOmittedCount()).isZero();
	}

	@Test
	void eachApiOriginKeepsASeparateBoundedCodeReserveAndCountsBothKindsOfOmissions() {
		for (ApiEndpoint.Origin origin : ApiEndpoint.Origin.values()) {
			AnalysisReport report = report();
			int general = origin == ApiEndpoint.Origin.CODE_CONTEXT ? 250 : 750;
			int reserve = origin == ApiEndpoint.Origin.CODE_CONTEXT ? 75 : 150;
			for (int i = 0; i <= general; i++) {
				report.addApi(new ApiEndpoint(origin, "GET", "https://sdk.test/" + i, "assets/sdk.json:" + i, null));
			}
			ApiEndpoint first = report.getApis().get(0);
			for (int i = 0; i <= reserve; i++) {
				JadxNodeRef ref = node(i % 2 == 0 ? "com.example.app.Api" : "a.b");
				report.addApi(new ApiEndpoint(origin, "GET", "https://app.test/" + i + "?token=original-" + i, ref + ":" + i, ref));
			}
			assertThat(report.getApis()).hasSize(general + reserve).contains(first);
			assertThat(report.getApis().get(0)).isSameAs(first);
			assertThat(report.getApis().get(general).getAddress()).contains("token=original-0");
			String kind = origin.name().toLowerCase(java.util.Locale.ROOT);
			assertThat(report.getBudgetOmissions()).containsEntry("api.general." + kind, 1)
					.containsEntry("api.code_reserve." + kind, 1);
		}
	}

	@Test
	void findingGlobalReserveHasAHardUpperBound() {
		AnalysisReport report = report();
		AnalysisRules rules = new AnalysisRules(report);
		for (Kind kind : Kind.values()) {
			for (int i = 0; i < 150; i++) {
				rules.add(kind, "resource-" + i, "assets/sdk.txt:" + i, null, "raw-" + i);
			}
		}
		assertThat(report.getFindings()).hasSize(1000);
		for (Kind kind : Kind.values()) {
			for (int i = 0; i < 200; i++) {
				rules.add(kind, "business-" + i, "com.example.app.Code:" + i, node("com.example.app.Code"), "raw-" + i);
			}
		}
		assertThat(report.getFindings()).hasSize(1250);
		assertThat(report.getBudgetOmittedCount()).isPositive();
	}

	private static AnalysisReport report() {
		AnalysisReport report = new AnalysisReport();
		report.setAppPackage("com.example.app");
		return report;
	}

	private static JadxNodeRef node(String cls) {
		return new JadxNodeRef(RefType.METHOD, cls, "read()V");
	}
}
