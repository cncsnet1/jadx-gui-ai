package jadx.gui.ai.analysis;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jadx.gui.ai.AiEvidence;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;

class InvestigationResumeTest {
	private static final String ENGINE = "same-engine";

	@Test
	void currentSourcesResumeAfterSnapshotAndJsonRoundTrip() {
		AnalysisReport report = report();
		AnalysisFinding finding = finding("F1", "first source");
		report.add(finding);
		InvestigationRun run = run(finding);
		var gson = defaultGsonBuilder().disableJdkUnsafe().create();
		InvestigationRun saved = gson.fromJson(gson.toJson(run.snapshot()), InvestigationRun.class);
		assertThat(InvestigationService.canResume(report.snapshot(), saved, ENGINE)).isTrue();
		assertThat(saved.getEvidence().entries()).hasSize(1);
	}

	@Test
	void sameFindingNumberCannotResumeWithAReassignedSource() {
		AnalysisFinding initial = finding("F1", "first source");
		InvestigationRun run = run(initial);
		AnalysisReport rescanned = report();
		rescanned.add(finding("F1", "different source"));
		assertThat(InvestigationService.canResume(rescanned, run, ENGINE)).isFalse();
		assertThat(run.getEvidence().entries().get(0).getSource().getTitle()).isEqualTo("first source");
	}

	@Test
	void apiAndProfilePositionsAreAlsoBoundToTheirOriginalSources() {
		AnalysisReport initial = report();
		initial.addApi(new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "POST", "/login", "app.js:2", null));
		InvestigationRun apiRun = run(initial.getApis().get(0).asFinding());
		assertThat(InvestigationService.canResume(initial, apiRun, ENGINE)).isTrue();
		AnalysisReport changedApi = report();
		changedApi.addApi(new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "POST", "/different", "app.js:2", null));
		assertThat(InvestigationService.canResume(changedApi, apiRun, ENGINE)).isFalse();

		ApkProfile profile = new ApkProfile();
		profile.fact(ApkProfile.Kind.TECHNOLOGY, "technology_flutter", "Flutter", ApkProfile.Confidence.OBSERVED,
				List.of(source("base.apk!/lib/arm64-v8a/libflutter.so")));
		initial.setProfile(profile);
		InvestigationRun profileRun = run(InvestigationService.find(initial, "K1"));
		assertThat(InvestigationService.canResume(initial, profileRun, ENGINE)).isTrue();
		ApkProfile replacement = new ApkProfile();
		replacement.fact(ApkProfile.Kind.TECHNOLOGY, "technology_uniapp", "uni-app", ApkProfile.Confidence.OBSERVED,
				List.of(source("base.apk!/assets/apps/demo/www/app-service.js")));
		initial.setProfile(replacement);
		assertThat(InvestigationService.canResume(initial, profileRun, ENGINE)).isFalse();
	}

	@Test
	void legacyRunWithoutIdentityMapRemainsReadableButCannotResume() {
		AnalysisReport report = report();
		AnalysisFinding finding = finding("F1", "first source");
		report.add(finding);
		var gson = defaultGsonBuilder().disableJdkUnsafe().create();
		var legacy = gson.toJsonTree(run(finding)).getAsJsonObject();
		legacy.remove("selectedIdentities");
		InvestigationRun restored = gson.fromJson(legacy, InvestigationRun.class);
		assertThat(restored.getEvidence().entries()).hasSize(1);
		assertThat(InvestigationService.canResume(report, restored, ENGINE)).isFalse();
		assertThat(InvestigationService.canResume(report, restored.snapshot(), ENGINE)).isFalse();
	}

	@Test
	void fingerprintEngineAndScanCompletionMustAllMatch() {
		AnalysisReport report = report();
		AnalysisFinding finding = finding("F1", "source");
		report.add(finding);
		InvestigationRun run = run(finding);
		assertThat(InvestigationService.canResume(report, run, "new-engine")).isFalse();
		report.setEngineProfile("new-engine");
		assertThat(InvestigationService.canResume(report, run, ENGINE)).isFalse();
		report.setEngineProfile(ENGINE);
		report.setFingerprint("changed-input");
		assertThat(InvestigationService.canResume(report, run, ENGINE)).isFalse();
		report.setFingerprint("same-input");
		report.setComplete(false);
		assertThat(InvestigationService.canResume(report, run, ENGINE)).isFalse();
	}

	private static AnalysisReport report() {
		AnalysisReport report = new AnalysisReport();
		report.setFingerprint("same-input");
		report.setEngineProfile(ENGINE);
		report.setComplete(true);
		return report;
	}

	private static AnalysisFinding finding(String id, String title) {
		return new AnalysisFinding(id, AnalysisFinding.Kind.CRYPTO, "AES/GCM/NoPadding", source(title));
	}

	private static AiEvidence source(String title) {
		return new AiEvidence(null, title, "observed structure", AiEvidence.fingerprint(title), false);
	}

	private static InvestigationRun run(AnalysisFinding finding) {
		InvestigationRun run = new InvestigationRun("same-input", ENGINE, AnalysisGoal.BEHAVIOR, "", finding.getId());
		run.select(List.of(finding));
		run.retain("initial", finding.getEvidence().get(0), List.of(), false, Set.of(finding.getId()));
		return run;
	}
}
