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

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssessmentTest {
	private static AnalysisReport report(String fingerprint, boolean extraFirst, String code) {
		AnalysisReport report = new AnalysisReport();
		report.setFingerprint(fingerprint);
		AnalysisRules rules = new AnalysisRules(report);
		if (extraFirst) {
			rules.scan("https://example.test/public", "Other", null);
		}
		rules.scan(code, "assets/config.txt", null);
		report.setComplete(true);
		return report;
	}

	private static AssessmentItem credential(AnalysisReport report) {
		return AssessmentItem.from(report).stream().filter(i -> i.group().equals("secret")).findFirst().orElseThrow();
	}

	@Test
	void prioritiesRemainSeparateFromHumanSeverityAndEvidenceType() {
		AnalysisReport report = report("apk", true, "password=fixture-secret");
		AssessmentItem secret = credential(report);
		assertThat(secret.priority()).isEqualTo(AssessmentItem.Priority.FIRST);
		assertThat(report.review(secret.key()).getSeverity()).isEqualTo(Severity.UNASSESSED);
		assertThat(report.review(secret.key()).getStatus()).isEqualTo(Status.UNREVIEWED);
		assertThat(secret.detail()).contains(Kind.CREDENTIAL.text("impact"), Kind.CREDENTIAL.text("gap"));
		assertThat(AssessmentItem.from(report)).hasSize(2); // API address does not duplicate its raw URL finding.
	}

	@Test
	void verificationRequiresEvidenceNotesAndRejectsPartialOrStaleItems() {
		assertThatThrownBy(() -> new AssessmentReview(Status.REPRODUCED, Severity.HIGH, " "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AssessmentReview(Status.DISMISSED, Severity.UNASSESSED, ""))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AssessmentReview(Status.INVESTIGATING, Severity.UNASSESSED, "x".repeat(4001)))
				.isInstanceOf(IllegalArgumentException.class);
		AnalysisReport report = report("apk", false, "password=fixture-secret");
		AssessmentItem item = credential(report);
		report.setComplete(false);
		assertThatThrownBy(() -> report.review(item, AssessmentReview.unreviewed())).isInstanceOf(IllegalArgumentException.class);
		AnalysisReport other = report("apk", false, "password=different-secret");
		assertThatThrownBy(() -> other.review(item, AssessmentReview.unreviewed())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rescanCarriesReviewsAcrossOrdinalChangesButNotInputOrEvidenceChanges() {
		AnalysisReport first = report("apk", false, "password=fixture-secret");
		first.review(credential(first), new AssessmentReview(Status.NEEDS_RUNTIME, Severity.UNASSESSED, "Need a test environment"));
		var gson = defaultGsonBuilder().create();
		AnalysisReport restored = gson.fromJson(gson.toJson(first), AnalysisReport.class);
		AnalysisReport reordered = report("apk", true, "password=fixture-secret");
		reordered.carryReviews(restored);
		assertThat(credential(first).finding().getId()).isNotEqualTo(credential(reordered).finding().getId());
		assertThat(reordered.review(credential(reordered).key()).getStatus()).isEqualTo(Status.NEEDS_RUNTIME);
		for (AnalysisReport changed : List.of(report("changed-apk", false, "password=fixture-secret"),
				report("", false, "password=fixture-secret"), report("apk", false, "password=different-secret"))) {
			changed.carryReviews(restored);
			assertThat(changed.review(credential(changed).key()).getStatus()).isEqualTo(Status.UNREVIEWED);
		}
	}

	@Test
	void reviewsOfOlderReportsMigrateOnlyWhenEvidenceIdentityIsUnambiguous() {
		var gson = defaultGsonBuilder().create();
		AnalysisReport fresh = report("apk", false, "password=fixture-secret");
		var json = gson.toJsonTree(fresh).getAsJsonObject();
		json.getAsJsonArray("findings").forEach(f -> f.getAsJsonObject().remove("reviewKey"));
		AnalysisReport old = gson.fromJson(json, AnalysisReport.class);
		old.review(credential(old), new AssessmentReview(Status.INVESTIGATING, Severity.UNASSESSED, "Old report review"));
		fresh.carryReviews(old);
		assertThat(fresh.review(credential(fresh).key()).getStatus()).isEqualTo(Status.INVESTIGATING);
		AnalysisReport ambiguous = report("apk", false, "String password = \"first-secret\", client_secret = \"second-secret\";");
		var ambiguousJson = gson.toJsonTree(ambiguous).getAsJsonObject();
		ambiguousJson.getAsJsonArray("findings").forEach(f -> f.getAsJsonObject().remove("reviewKey"));
		AnalysisReport oldAmbiguous = gson.fromJson(ambiguousJson, AnalysisReport.class);
		oldAmbiguous.review(credential(oldAmbiguous), new AssessmentReview(Status.INVESTIGATING, Severity.UNASSESSED, "Ambiguous"));
		ambiguous.carryReviews(oldAmbiguous);
		assertThat(AssessmentItem.from(ambiguous)).allMatch(i -> ambiguous.review(i.key()).getStatus() == Status.UNREVIEWED);
	}

	@Test
	void secretsOnTheSameLineDoNotShareAReviewKeyAfterPersistence() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		String code = "String password = \"first-secret\", client_secret = \"second-secret\";";
		rules.scan(code, "Client", null);
		var gson = defaultGsonBuilder().create();
		AnalysisReport restored = gson.fromJson(gson.toJson(report), AnalysisReport.class);
		assertThat(AssessmentItem.from(restored)).extracting(AssessmentItem::key).doesNotHaveDuplicates().hasSize(2);
	}

	@Test
	void snapshotsAndProjectSerializationPreserveIndependentManualRecords() {
		AnalysisReport report = report("apk", false, "password=fixture-secret");
		AssessmentItem item = credential(report);
		report.review(item, new AssessmentReview(Status.INVESTIGATING, Severity.UNASSESSED, "Investigate usage"));
		AnalysisReport snapshot = report.snapshot();
		report.review(item, new AssessmentReview(Status.DISMISSED, Severity.INFO, "Test-only code; see build fixture"));
		assertThat(snapshot.review(item.key()).getStatus()).isEqualTo(Status.INVESTIGATING);
		var gson = defaultGsonBuilder().create();
		String json = gson.toJson(report);
		assertThat(json).doesNotContain("fixture-secret");
		AssessmentReview restored = gson.fromJson(json, AnalysisReport.class).review(item.key());
		assertThat(restored.getStatus()).isEqualTo(Status.DISMISSED);
		assertThat(restored.getNote()).contains("Test-only code");
		assertThat(restored.getUpdatedAt()).isPositive();
		String oldJson = "{\"fingerprint\":\"old\",\"complete\":true,\"findings\":[],\"apis\":[]}";
		assertThat(gson.fromJson(oldJson, AnalysisReport.class).review("missing").getStatus()).isEqualTo(Status.UNREVIEWED);
	}

	@Test
	void reportMarksHumanOutcomesAndRedactsNotesOnExportWithoutChangingLocalNotes() {
		AnalysisReport report = report("apk", false, "password=fixture-secret");
		AssessmentItem item = credential(report);
		report.review(item, new AssessmentReview(Status.REPRODUCED, Severity.HIGH,
				"Evidence saved in test logs.\npassword=private-note-secret\n````<script>"));
		assertThat(report.markdown()).contains(AiText.str("assessment.state"), Status.REPRODUCED.toString(),
				Severity.HIGH.toString(), "Evidence saved in test logs.")
				.doesNotContain("private-note-secret", "fixture-secret", "````<script>");
		assertThat(report.review(item.key()).getNote()).contains("private-note-secret");
	}

	@Test
	void apiReviewIdentitySurvivesIdsButChangesWithRequestMetadata() {
		AnalysisReport first = new AnalysisReport();
		first.setFingerprint("apk");
		first.setComplete(true);
		first.addApi(new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "POST", "/session", "Api.login", null));
		first.review(AssessmentItem.from(first).get(0), new AssessmentReview(Status.NEEDS_RUNTIME, Severity.UNASSESSED, "Check roles"));
		AnalysisReport next = new AnalysisReport();
		next.setFingerprint("apk");
		next.addApi(new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "", "https://example.test/", "Docs", null));
		next.addApi(new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "POST", "/session", "Api.login", null));
		next.carryReviews(first);
		AssessmentItem api =
				AssessmentItem.from(next).stream().filter(i -> i.finding().getKind() == Kind.HTTP_ROUTE).findFirst().orElseThrow();
		assertThat(api.finding().getId()).isEqualTo("A2");
		assertThat(next.review(api.key()).getStatus()).isEqualTo(Status.NEEDS_RUNTIME);
		next.getApis().get(1).header("Authorization: Bearer fixture-token");
		AssessmentItem changed =
				AssessmentItem.from(next).stream().filter(i -> i.finding().getKind() == Kind.HTTP_ROUTE).findFirst().orElseThrow();
		assertThat(next.review(changed.key()).getStatus()).isEqualTo(Status.UNREVIEWED);
	}

	@Test
	void securityCatalogIncludesApiAndComponentEvidenceDespiteLargeSecretCategory() {
		AnalysisReport report = new AnalysisReport();
		for (int i = 0; i < 100; i++) {
			report.add(new AnalysisFinding("F" + i, Kind.CREDENTIAL, "secret" + i,
					new AiEvidence(null, "Config" + i, "[redacted]", "hash" + i, false)));
		}
		report.add(new AnalysisFinding("F101", Kind.EXPORTED, "component", new AiEvidence(null, "Manifest", "exported", "", false)));
		report.addApi(new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "GET", "/users/{id}", "Api.users", null));
		List<AnalysisFinding> candidates = AnalysisInvestigator.candidates(report, AnalysisGoal.SECURITY);
		assertThat(candidates).hasSize(60);
		assertThat(candidates.subList(0, 6)).extracting(AnalysisFinding::getKind).contains(Kind.CREDENTIAL, Kind.EXPORTED, Kind.HTTP_ROUTE);
	}

	@Test
	void aiCannotSeeOrOverwriteHumanReviewAndUsesTheSecurityInstructions() throws Exception {
		AnalysisReport report = report("apk", false, "password=fixture-secret");
		AssessmentItem item = credential(report);
		report.review(item, new AssessmentReview(Status.DISMISSED, Severity.INFO, "HumanOnlyNote7824"));
		List<String> sent = new ArrayList<>();
		AiClient client = (model, messages, temperature, tokens, cancellation, output) -> {
			sent.add(messages.toString());
			return sent.size() == 1 ? "{\"inspect\":[\"F1\"]}" : "A hypothesis [S1]";
		};
		new AnalysisInvestigator(null, client).investigate(report, AnalysisGoal.SECURITY, "", null,
				new AiProfile(), new AiCancellation(), ignored -> {
				});
		assertThat(sent).hasSize(2);
		assertThat(sent.get(1)).contains(AiText.str("assessment.prompt"));
		assertThat(sent.toString()).doesNotContain("HumanOnlyNote7824", "fixture-secret");
		assertThat(report.review(item.key()).getStatus()).isEqualTo(Status.DISMISSED);
	}
}
