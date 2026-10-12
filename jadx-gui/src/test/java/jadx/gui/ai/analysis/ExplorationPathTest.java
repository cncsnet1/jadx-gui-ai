package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.AiText;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;
import jadx.gui.ai.analysis.AssessmentReview.Severity;
import jadx.gui.ai.analysis.AssessmentReview.Status;

import static org.assertj.core.api.Assertions.assertThat;

class ExplorationPathTest {
	@Test
	void everyLeadHasASuggestedRouteAndNextStageWithoutARequiredAssessment() {
		for (Kind kind : Kind.values()) {
			AnalysisReport report = new AnalysisReport();
			report.add(new AnalysisFinding("F1", kind, "source", new AiEvidence(null, "Entry.location", "Observed content", "", false)));
			AssessmentItem item = AssessmentItem.from(report).get(0);
			String detail = ExplorationPath.detail(item);
			assertThat(detail).contains("Entry.location", "Observed content", kind.text("route"), kind.text("handoff"),
					AiText.str("explore.boundary")).doesNotContain(AiText.str("assessment.severity"));
			assertThat(ExplorationPath.next(item)).isNotBlank();
			assertThat(ExplorationPath.direction(item)).isNotNull();
		}
	}

	@Test
	void selectedLeadPackageRetainsUnknownsAndDoesNotCombineUnrelatedEvidenceOrPrivateNotes() {
		AnalysisReport report = new AnalysisReport();
		ApiEndpoint api = new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "GET", "users/{id}?token=private-value", "Api.users", null);
		api.header("Authorization: Bearer secret-header");
		report.addApi(api);
		report.addApi(new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "", "https://unrelated.test/admin", "Documentation", null));
		report.setComplete(true);
		AssessmentItem selected =
				AssessmentItem.from(report).stream().filter(i -> i.finding().getId().equals("A1")).findFirst().orElseThrow();
		report.review(selected, new AssessmentReview(Status.INVESTIGATING, Severity.HIGH, "HumanNoteDoNotExportInPackage"));
		String markdown = ExplorationPath.markdown(selected, report);
		assertThat(markdown).contains("GET users/{id}", "Api.users", AiText.str("api.base_unknown"),
				AiText.str("explore.package_hint"), AiText.str("explore.boundary"));
		assertThat(markdown).doesNotContain("private-value", "secret-header", "unrelated.test", "HumanNoteDoNotExportInPackage");
		report.setComplete(false);
		assertThat(ExplorationPath.markdown(selected, report)).contains(AiText.str("analysis.partial"));
	}

	@Test
	void explorationIncludesApisAndAsksForSupportedAndMissingLinksInsteadOfRatings() throws Exception {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		for (int i = 0; i < 70; i++) {
			rules.scan("password=fixture-secret-" + i, "Config" + i, null);
		}
		report.addApi(new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "POST", "session", "Api.login", null));
		assertThat(AnalysisInvestigator.candidates(report, AnalysisGoal.EXPLORE).subList(0, 6))
				.extracting(AnalysisFinding::getKind).contains(Kind.HTTP_ROUTE, Kind.CREDENTIAL);
		List<String> sent = new ArrayList<>();
		AiClient client = (model, messages, temperature, tokens, cancellation, output) -> {
			sent.add(messages.toString());
			return sent.size() == 1 ? "{\"inspect\":[\"A1\"]}" : "Trace the client creation next [S1]";
		};
		new AnalysisInvestigator(null, client).investigate(report, AnalysisGoal.EXPLORE, "", null, new AiProfile(),
				new AiCancellation(), ignored -> {
				});
		assertThat(sent).hasSize(2);
		assertThat(sent.get(1)).contains(AiText.str("explore.prompt"), "Api.login").doesNotContain(AiText.str("assessment.prompt"));
	}
}
