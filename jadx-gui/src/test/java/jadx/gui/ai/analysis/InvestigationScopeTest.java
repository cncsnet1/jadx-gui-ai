package jadx.gui.ai.analysis;

import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationScopeTest {
	@Test
	void focusedCatalogPreservesSelectionWithoutPullingUnrelatedSdkOrFrameworkSources() {
		AnalysisReport report = new AnalysisReport();
		report.setAppPackage("com.example.app");
		AnalysisFinding selected = finding("F1", AnalysisFinding.Kind.TOKEN, "original-token", "com.example.app.Session");
		report.add(selected);
		report.add(finding("F2", AnalysisFinding.Kind.CRYPTO, "Cipher", "com.example.app.Session"));
		report.add(finding("F3", AnalysisFinding.Kind.TOKEN, "unrelated-sdk-token", "com.google.firebase.Other"));
		FrameworkAnalysis framework = new FrameworkAnalysis();
		AiEvidence unrelated = new AiEvidence(null, "app.apk!/assets/unrelated.js", "secret", "fixture", false);
		framework.route("cordova", "Cordova", List.of(unrelated), "Read unrelated script");
		framework.activate("cordova");
		framework.add(new FrameworkAnalysis.Finding(FrameworkAnalysis.Kind.REQUESTS, "unrelated-script-request", "", "", unrelated));
		report.setFrameworkAnalysis(framework);
		InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.BEHAVIOR, "Trace selected", selected.getId());
		run.select(List.of(selected));
		assertThat(InvestigationScope.leads(report, run)).extracting(AnalysisFinding::getId).containsExactly("F1", "F2");
		try (jadx.api.JadxDecompiler decompiler = new jadx.api.JadxDecompiler()) {
			String catalog = new LocalAnalysisTools(decompiler).catalog(run, report);
			assertThat(catalog).contains("original-token", "Cipher").doesNotContain("unrelated-sdk-token", "unrelated-script-request",
					"Cordova");
		}
	}

	@Test
	void automaticSeedsAvoidWeakBackgroundButExplicitSelectionRemainsAvailable() {
		AnalysisReport report = new AnalysisReport();
		AnalysisFinding meaningful = finding("F1", AnalysisFinding.Kind.CRYPTO, "Cipher", "a.b.c");
		AnalysisFinding background = finding("F2", AnalysisFinding.Kind.ENDPOINT, "https://original.example", "com.google.firebase.Config");
		report.add(background);
		report.add(meaningful);
		assertThat(InvestigationScope.seeds(report, AnalysisInvestigator.candidates(report, AnalysisGoal.NETWORK)))
				.extracting(AnalysisFinding::getId).containsExactly("F1");
		InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.NETWORK, "Inspect address", "F2");
		run.select(List.of(background));
		assertThat(InvestigationScope.leads(report, run)).extracting(AnalysisFinding::getValue).containsExactly("https://original.example");
		assertThat(InvestigationScope.sameSource(new AiEvidence(null, "generic metadata", "a", "a", false),
				new AiEvidence(null, "generic metadata", "b", "b", false))).isFalse();
	}

	@Test
	void planningKeepsSelectedEvidenceReadableAfterManyNewToolResults() {
		AnalysisReport report = new AnalysisReport();
		AnalysisFinding finding = finding("F1", AnalysisFinding.Kind.TOKEN, "original-focus-token", "app.Session");
		report.add(finding);
		InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.BEHAVIOR, "Continue", "F1");
		run.select(List.of(finding));
		String original = run.retain("initial", finding.getEvidence().get(0), List.of(), false, java.util.Set.of("F1"));
		for (int i = 0; i < 12; i++) {
			run.retain("search_artifacts", new AiEvidence(null, "Other result " + i, "new observation ".repeat(300), "result" + i, false),
					List.of(), false, java.util.Set.of());
		}
		try (jadx.api.JadxDecompiler decompiler = new jadx.api.JadxDecompiler()) {
			var messages = new InvestigationService(decompiler, (model, input, temperature, budget, token, output) -> {
				throw new AssertionError("Planning context must not request a model");
			}).planningMessages(run, report);
			String prompt = messages.get(1).get("content");
			assertThat(prompt).contains(original + " | selected source anchor", "original-focus-token", "Other result 11");
			assertThat(prompt.substring(prompt.indexOf("Completed observations"), prompt.indexOf("Recent tool attempts")))
					.hasSizeLessThan(19_000);
		}
	}

	private static AnalysisFinding finding(String id, AnalysisFinding.Kind kind, String value, String cls) {
		return new AnalysisFinding(id, kind, value, new AiEvidence(new JadxNodeRef(RefType.METHOD, cls, "load()V"), cls,
				value, AiEvidence.fingerprint(value), false));
	}
}
