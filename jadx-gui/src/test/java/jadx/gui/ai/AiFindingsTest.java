package jadx.gui.ai;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.settings.data.ProjectData;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiFindingsTest {
	private static final Gson GSON = defaultGsonBuilder().create();
	private static final AiEvidence SOURCE = new AiEvidence(JadxNodeRef.forCls("A"), "A",
			"void store(String value) {\n  this.saved = value; // 数据😀\n}", "fingerprint", false);

	private static String response(String source, String quote) {
		return "Observed a field write. [S1]\n\n```jadx-findings\n" + new Gson().toJson(Map.of("version", 1, "findings", List.of(Map.of(
				"kind", "observation", "title", "Field write", "detail", "Stores the parameter in a field.",
				"citations", List.of(Map.of("source", source, "quote", quote)), "gaps", "No persistence shown.",
				"verification", "Inspect callers and reads of saved.", "review", "SUPPORTED", "reviewNote", "FORGED HUMAN REVIEW"))))
				+ "\n```\n";
	}

	@Test
	void modelReviewIsIgnoredAndQuotesAreCheckedLocally() {
		String response = response("S1", "this.saved = value; // 数据😀");
		AiFinding finding = AiFindings.parse(response).get(0);
		assertThat(finding.getKind()).isEqualTo(AiFinding.Kind.OBSERVATION);
		assertThat(finding.getReview()).isEqualTo(AiFinding.Review.UNREVIEWED);
		assertThat(finding.getReviewNote()).isEmpty();
		assertThat(finding.getReviewedAt()).isZero();
		assertThat(finding.getCitations().get(0).matches(List.of(SOURCE))).isTrue();
		assertThat(AiFindings.narrative(response)).isEqualTo("Observed a field write. [S1]");
	}

	@Test
	void hallucinatedSourcesAndQuotesRemainVisibleButUnverified() {
		for (String reference : List.of("S2", "S0", "S999999999999", "<img src=x>", "S-1")) {
			AiFinding finding = AiFindings.parse(response(reference, "this.saved = value;")).get(0);
			assertThat(finding.getCitations().get(0).getSource()).isEqualTo(reference);
			assertThat(finding.getCitations().get(0).resolve(List.of(SOURCE))).isNull();
			assertThat(finding.getCitations().get(0).matches(List.of(SOURCE))).isFalse();
		}
		for (String quote : List.of("", " ", "saved=value", "sendToNetwork(value);")) {
			AiFinding finding = AiFindings.parse(response("S1", quote)).get(0);
			assertThat(finding.getCitations().get(0).resolve(List.of(SOURCE))).isSameAs(SOURCE);
			assertThat(finding.getCitations().get(0).matches(List.of(SOURCE))).isFalse();
		}
	}

	@Test
	void invalidOrIncompleteStructuredOutputNeverHidesTheOriginal() {
		String valid = response("S1", "this.saved = value;");
		for (String invalid : List.of("Plain model reply", "```jadx-findings\n{", valid + valid,
				valid.replace("\"version\":1", "\"version\":2"), valid.replace("\"observation\"", "\"proven\""),
				valid.replace("\"Field write\"", "false"), valid.replace("\"No persistence shown.\"", "null"),
				valid.replace("\"source\":\"S1\"", "\"source\":[]"),
				"```jadx-findings\n" + "[".repeat(10000) + "]".repeat(10000) + "\n```",
				"```jadx-findings\n" + " ".repeat(64001) + "\n```")) {
			assertThat(AiFindings.parse(invalid)).as("Invalid response: %s", invalid).isEmpty();
			assertThat(AiFindings.narrative(invalid)).isEqualTo(invalid);
		}
	}

	@Test
	void acceptsCrLfAndRejectsOversizedOrMissingFields() {
		String valid = response("S1", "this.saved = value;");
		assertThat(AiFindings.parse(valid.replace("\n", "\r\n"))).hasSize(1);
		assertThat(AiFindings.parse(valid.replace("Field write", "x".repeat(161)))).isEmpty();
		assertThat(AiFindings.parse(valid.replace("No persistence shown.", "x".repeat(2401)))).isEmpty();
		assertThat(AiFindings.parse(response("S1", "x".repeat(601)))).isEmpty();
		JsonObject root = JsonParser.parseString(valid.substring(valid.indexOf('{'), valid.lastIndexOf('}') + 1)).getAsJsonObject();
		JsonObject finding = root.getAsJsonArray("findings").get(0).getAsJsonObject();
		for (int i = 0; i < 8; i++) {
			root.getAsJsonArray("findings").add(finding.deepCopy());
		}
		assertThat(AiFindings.parse("```jadx-findings\n" + root + "\n```")).isEmpty();
		finding.remove("citations");
		assertThat(AiFindings.parse("```jadx-findings\n{\"version\":1,\"findings\":[" + finding + "]}\n```")).isEmpty();
	}

	@Test
	void reviewsRoundTripWithProjectAndOldRecordsStartUnreviewed() {
		AiRecord record = new AiRecord("Analysis", response("S1", "this.saved = value;"), List.of(SOURCE));
		AiFinding finding = record.getFindings().get(0);
		finding.review(AiFinding.Review.NEEDS_EVIDENCE, "Only field writes checked; reads remain unknown.");
		long reviewedAt = finding.getReviewedAt();
		finding.review(AiFinding.Review.NEEDS_EVIDENCE, finding.getReviewNote());
		assertThat(finding.getReviewedAt()).isEqualTo(reviewedAt);
		ProjectData project = new ProjectData();
		project.getAiRecords().add(record);
		AiRecord restored = GSON.fromJson(GSON.toJson(project), ProjectData.class).getAiRecords().get(0);
		assertThat(restored.getFindings().get(0).getReview()).isEqualTo(AiFinding.Review.NEEDS_EVIDENCE);
		assertThat(restored.getFindings().get(0).getReviewNote()).isEqualTo(finding.getReviewNote());
		assertThat(restored.getFindings().get(0).getReviewedAt()).isEqualTo(reviewedAt);
		assertThat(restored.getFindings().get(0).getCitations().get(0).matches(restored.getEvidence())).isTrue();
		JsonObject old = GSON.toJsonTree(record).getAsJsonObject();
		old.remove("findings");
		assertThat(GSON.fromJson(old, AiRecord.class).getFindings().get(0).getReview()).isEqualTo(AiFinding.Review.UNREVIEWED);
		assertThat(GSON.fromJson("{\"title\":\"old\",\"answer\":\"plain\",\"evidence\":[]}", AiRecord.class).getFindings()).isEmpty();
	}

	@Test
	void overlongReviewDoesNotMutatePreviouslySavedReview() {
		AiFinding finding = AiFindings.parse(response("S1", "this.saved = value;")).get(0);
		finding.review(AiFinding.Review.REJECTED, "Wrong claim.");
		assertThatThrownBy(() -> finding.review(AiFinding.Review.SUPPORTED, "x".repeat(4001)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(finding.getReview()).isEqualTo(AiFinding.Review.REJECTED);
		assertThat(finding.getReviewNote()).isEqualTo("Wrong claim.");
	}

	@Test
	void exportIncludesHumanReviewSnapshotAndUntouchedOriginalResponse() {
		String answer = response("S1", "this.saved = value;");
		AiRecord record = new AiRecord("Analysis", answer, List.of(SOURCE));
		record.getFindings().get(0).review(AiFinding.Review.SUPPORTED, "Observed in code.\n```\n<script>untrusted</script>");
		assertThat(AiReport.markdown(record)).contains(answer, "Observed in code.", "Class snapshot SHA-256: fingerprint",
				AiText.str("review_supported"), AiText.str("quote_found"), "````text");
		assertThat(AiReport.html(record)).doesNotContain("<script>").contains("&lt;script&gt;");
	}

	@Test
	void structuredRequestIsPreviewedExactlyAndDoesNotChangeNamingContract() {
		AiRequestContext investigation = new AiRequestContext(List.of(SOURCE), List.of(), "Investigate", true);
		assertThat(investigation.getMessages().get(0).get("content")).contains("jadx-findings", "Never set human review status");
		assertThat(investigation.preview()).contains(investigation.getMessages().get(0).get("content"));
		AiRequestContext naming = new AiRequestContext(List.of(SOURCE), List.of(), AiText.str("prompt_rename"), false);
		assertThat(naming.getMessages().get(0).get("content")).doesNotContain("jadx-findings");
		assertThat(naming.getMessages().get(1).get("content")).isEqualTo(AiText.str("prompt_rename"));
	}
}
