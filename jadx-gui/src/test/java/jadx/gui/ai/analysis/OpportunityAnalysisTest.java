package jadx.gui.ai.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiRecord;
import jadx.gui.ai.AiReport;
import jadx.gui.ai.AiText;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;
import jadx.gui.ai.analysis.OpportunityAnalysis.Priority;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;

class OpportunityAnalysisTest {
	private static final List<AiEvidence> SOURCES = List.of(
			new AiEvidence(null, "API metadata", "GET /users/{id}", "", false),
			new AiEvidence(null, "Definition", "return api.user(userId);", "hash", false),
			new AiEvidence(null, "Unrelated definition", "return admin.deleteAll();", "hash2", false));
	private static final Map<String, Set<String>> OWNERS = Map.of("F1", Set.of("S1", "S2"), "F2", Set.of("S3"));
	private static final List<AnalysisFinding> LEADS = List.of(
			new AnalysisFinding("F1", Kind.HTTP_ROUTE, "users", SOURCES.get(0)),
			new AnalysisFinding("F2", Kind.DYNAMIC, "admin", SOURCES.get(2)));

	static JsonObject card(String leadId, String source, String quote) {
		JsonObject card = new JsonObject();
		Map.of("leadId", leadId, "title", "Inspect object access", "priority", "FIRST", "access", "ACCOUNT",
				"effort", "LOW", "protection", "NONE_IN_SCOPE", "impact", "SENSITIVE").forEach(card::addProperty);
		Map.of("prerequisites", "Use two controlled test accounts; identify their objects.",
				"guards", "No check observed in this client slice; server checks unknown.",
				"impactDetail", "Only if authorization fails, another account's object may be accessible.",
				"rationale", "The route has an explicit object identifier; inspect it before generic URL strings.",
				"gaps", "An authorization check on the server could refute this hypothesis.",
				"next", "Trace the supplied identifier and confirm expected ownership enforcement in a test environment.")
				.forEach(card::addProperty);
		JsonArray citations = new JsonArray();
		JsonObject citation = new JsonObject();
		citation.addProperty("source", source);
		citation.addProperty("quote", quote);
		citations.add(citation);
		card.add("citations", citations);
		return card;
	}

	static String response(JsonObject... cards) {
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		JsonArray array = new JsonArray();
		for (JsonObject card : cards) {
			array.add(card);
		}
		root.add("candidates", array);
		return "Readable comparison\n\n```jadx-opportunities\n" + root + "\n```\n";
	}

	private OpportunityAnalysis parse(String answer) {
		return OpportunityAnalysis.parse(answer, LEADS, 60, SOURCES, OWNERS, Set.of("S2", "S3"));
	}

	@Test
	void sourceBoundCandidateRetainsConditionsWithoutClaimingVerification() {
		OpportunityAnalysis analysis = parse(response(card("F1", "S2", "api.user(userId)")));
		assertThat(analysis.isParsed()).isTrue();
		assertThat(analysis.getCandidates()).singleElement().satisfies(c -> {
			assertThat(c.getPriority()).isEqualTo(Priority.FIRST);
			assertThat(c.getLimitations()).isEmpty();
			assertThat(c.detail()).contains(AiText.str("opportunity.inference"), "server", "test accounts", "authorization",
					AiText.str("opportunity.quotes"));
		});
		assertThat(analysis.getCatalogSize()).isEqualTo(60);
		assertThat(analysis.getSelectedIds()).containsExactly("F1", "F2");
	}

	@Test
	void exactQuotesCannotBeBorrowedFromAnotherLeadAndGuessedIdsAreRejected() {
		for (JsonObject invalid : List.of(card("F1", "S3", "admin.deleteAll()"), card("F99", "S2", "api.user"),
				card("F1", "S2", "no authorization required"), card("F1", "S0", "api.user"), card("F1", "S99", "api.user"),
				card("F1", "S2", " "))) {
			String answer = response(invalid);
			OpportunityAnalysis analysis = parse(answer);
			assertThat(analysis.getCandidates()).isEmpty();
			assertThat(analysis.getRejected()).isEqualTo(1);
			assertThat(analysis.narrative(answer)).isEqualTo(answer);
		}
	}

	@Test
	void oneBadCitationRejectsTheCardAndCannotHideAmongValidCitations() {
		JsonObject candidate = card("F1", "S2", "api.user");
		candidate.getAsJsonArray("citations").add(card("F1", "S3", "admin.deleteAll()").getAsJsonArray("citations").get(0));
		OpportunityAnalysis analysis = parse(response(candidate, card("F2", "S3", "admin.deleteAll()")));
		assertThat(analysis.getRejected()).isEqualTo(1);
		assertThat(analysis.getCandidates()).extracting(OpportunityAnalysis.Candidate::getLeadId).containsExactly("F2");
	}

	@Test
	void stringOnlyOrUnknownAndGuardedCandidatesCannotReceiveFirstPriority() {
		OpportunityAnalysis metadata = parse(response(card("F1", "S1", "GET /users/{id}")));
		assertThat(metadata.getCandidates().get(0).getPriority()).isEqualTo(Priority.INVESTIGATE);
		assertThat(metadata.getCandidates().get(0).getLimitations()).contains("code");
		for (String field : List.of("access", "effort", "protection", "impact")) {
			JsonObject candidate = card("F1", "S2", "api.user");
			candidate.addProperty(field, "UNKNOWN");
			assertThat(parse(response(candidate)).getCandidates().get(0).getPriority()).isEqualTo(Priority.INVESTIGATE);
		}
		JsonObject candidate = card("F1", "S2", "api.user");
		candidate.addProperty("effort", "HIGH");
		candidate.addProperty("protection", "PRESENT");
		assertThat(parse(response(candidate)).getCandidates().get(0).getLimitations()).contains("effort", "guard");
		assertThat(parse(response(candidate)).getCandidates().get(0).getPriority()).isEqualTo(Priority.INVESTIGATE);
		AnalysisFinding address = new AnalysisFinding("F1", Kind.ENDPOINT, "https://example.test", SOURCES.get(0));
		OpportunityAnalysis urls = OpportunityAnalysis.parse(response(card("F1", "S2", "api.user")), List.of(address), 1,
				SOURCES, OWNERS, Set.of("S2"));
		assertThat(urls.getCandidates().get(0).getPriority()).isEqualTo(Priority.INVESTIGATE);
		assertThat(urls.getCandidates().get(0).getLimitations()).contains("address");
	}

	@Test
	void truncatedBehaviorSourcesCannotReceiveFirstPriority() {
		var sources = new java.util.ArrayList<>(SOURCES);
		sources.set(1, new AiEvidence(null, "Clipped method", "return api.user(userId);", "hash", true));
		var analysis = OpportunityAnalysis.parse(response(card("F1", "S2", "api.user")), LEADS, 60, sources, OWNERS, Set.of("S2"));
		assertThat(analysis.getCandidates().get(0).getPriority()).isEqualTo(Priority.INVESTIGATE);
		assertThat(analysis.getCandidates().get(0).getLimitations()).contains("truncated");
	}

	@Test
	void gatedPriorityControlsOrderAndModelReviewFieldsAreIgnored() {
		JsonObject first = card("F1", "S1", "GET");
		first.addProperty("review", "REPRODUCED");
		first.addProperty("reviewNote", "FabricatedHumanReview");
		OpportunityAnalysis analysis = parse(response(first, card("F2", "S3", "admin.deleteAll()")));
		assertThat(analysis.getCandidates()).extracting(OpportunityAnalysis.Candidate::getLeadId).containsExactly("F2", "F1");
		assertThat(defaultGsonBuilder().create().toJson(analysis)).doesNotContain("FabricatedHumanReview", "REPRODUCED");
	}

	@Test
	void invalidAndTruncatedBlocksPreserveOriginalTextWithoutManufacturingResults() {
		String valid = response(card("F1", "S2", "api.user"));
		for (String answer : List.of("unstructured", valid + valid, valid.substring(0, valid.length() - 4),
				"```jadx-opportunities\n{\"version\":1,\"candidates\":" + "[".repeat(50) + "0" + "]".repeat(50) + "}\n```",
				valid.replace("\"version\":1", "\"version\":\"1\""), valid.replace("Readable comparison", "x".repeat(160_001)),
				"```jadx-opportunities\n" + " ".repeat(64_001) + "\n```")) {
			OpportunityAnalysis analysis = parse(answer);
			assertThat(analysis.isParsed()).isFalse();
			assertThat(analysis.getCandidates()).isEmpty();
			assertThat(analysis.narrative(answer)).isEqualTo(answer);
		}
	}

	@Test
	void invalidEnumsLengthsTypesAndDuplicateLeadsAreBounded() {
		for (String field : List.of("priority", "access", "effort", "protection", "impact")) {
			JsonObject candidate = card("F1", "S2", "api.user");
			candidate.addProperty(field, "INVALID");
			assertThat(parse(response(candidate)).getCandidates()).isEmpty();
		}
		JsonObject candidate = card("F1", "S2", "api.user");
		candidate.addProperty("title", "x".repeat(161));
		assertThat(parse(response(candidate)).getCandidates()).isEmpty();
		candidate.addProperty("title", 1);
		assertThat(parse(response(candidate)).getCandidates()).isEmpty();
		candidate = card("F1", "S2", "api.user");
		assertThat(parse(response(candidate, candidate)).getCandidates()).hasSize(1);
		assertThat(parse(response(candidate, candidate)).getRejected()).isEqualTo(1);
		assertThat(parse(response(candidate, candidate, candidate, candidate, candidate, candidate, candidate)).isParsed()).isFalse();
	}

	@Test
	void persistenceAndExportKeepGatesCitationsOriginalAndLegacyCompatibility() {
		String answer = response(card("F1", "S1", "GET"));
		AiRecord record = new AiRecord("Investigation", answer, SOURCES);
		record.setOpportunities(parse(answer));
		var gson = defaultGsonBuilder().disableJdkUnsafe().create();
		AiRecord restored = gson.fromJson(gson.toJson(record), AiRecord.class);
		assertThat(restored.getNarrative()).isEqualTo("Readable comparison");
		assertThat(restored.getOpportunities().getCandidates().get(0).getPriority()).isEqualTo(Priority.INVESTIGATE);
		assertThat(restored.getOpportunities().getCandidates().get(0).getCitations().get(0).resolve(restored.getEvidence()).getCode())
				.isEqualTo("GET /users/{id}");
		assertThat(AiReport.markdown(restored)).contains(AiText.str("opportunity.title"), AiText.str("opportunity.limit.code"), answer);
		AiRecord old = gson.fromJson("{\"title\":\"old\",\"answer\":\"original\",\"evidence\":[]}", AiRecord.class);
		assertThat(old.getOpportunities()).isNull();
		assertThat(old.getNarrative()).isEqualTo("original");
	}

	@Test
	void emptyResultIsExplicitAndModelMarkupIsPlainExportData() {
		assertThat(parse(response()).isParsed()).isTrue();
		assertThat(parse(response()).diagnostics()).isEqualTo(AiText.str("opportunity.none"));
		JsonObject candidate = card("F1", "S2", "api.user");
		candidate.addProperty("title", "<img src='https://example.test/track'>");
		candidate.addProperty("next", "```\n[link](https://example.test/)\n```");
		AiRecord record = new AiRecord("Test", "Narrative", SOURCES);
		record.setOpportunities(parse(response(candidate)));
		assertThat(AiReport.html(record)).doesNotContain("<img", "href=\"https://example.test/");
		assertThat(AiReport.markdown(record)).contains("````text");
	}
}
