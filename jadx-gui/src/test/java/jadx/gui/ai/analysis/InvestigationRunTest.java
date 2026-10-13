package jadx.gui.ai.analysis;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jadx.gui.ai.AiEvidence;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationRunTest {
	@Test
	void evidenceIdentityIsScopedRedactedAndImmutableAcrossSnapshots() {
		AiEvidence source = new AiEvidence(null, "config", "password=\"fixture-password\"", "hash", false);
		EvidenceStore store = new EvidenceStore("apk-1", "profile-1");
		String id = store.retain("initial", source, List.of(), false);
		assertThat(store.retain("initial", source, List.of(), false)).isEqualTo(id);
		assertThat(new EvidenceStore("apk-2", "profile-1").retain("initial", source, List.of(), false)).isNotEqualTo(id);
		assertThat(new EvidenceStore("apk-1", "profile-2").retain("initial", source, List.of(), false)).isNotEqualTo(id);
		EvidenceStore snapshot = store.snapshot();
		store.retain("inspect_symbol", new AiEvidence(null, "next", "code", "", false), List.of(id), true);
		assertThat(snapshot.entries()).hasSize(1);
		assertThat(store.get(id).getSource().getCode()).doesNotContain("fixture-password");
		assertThatThrownBy(() -> store.retain("x", source, List.of("foreign"), false)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void persistedRunPreservesEvidenceStepsQuestionsAndIsolation() {
		InvestigationRun run = new InvestigationRun("apk", "profile", AnalysisGoal.NETWORK, "API paths", "A1");
		String id = run.retain("initial", new AiEvidence(null, "api", "GET /users", "hash", false), List.of(), false, Set.of());
		run.step("initial", "A1", "observed", List.of(id), "done");
		run.questions(List.of("Which client is used?"));
		run.stop(InvestigationRun.State.CANCELLED, "cancelled");
		AnalysisReport report = new AnalysisReport();
		report.setFingerprint("apk");
		report.setComplete(true);
		report.putInvestigation(run);
		var gson = defaultGsonBuilder().create();
		AnalysisReport restored = gson.fromJson(gson.toJson(report), AnalysisReport.class);
		InvestigationRun saved = restored.getInvestigations().get(0);
		assertThat(saved.getId()).isEqualTo(run.getId());
		assertThat(saved.getQuestions()).containsExactly("Which client is used?");
		assertThat(saved.getEvidence().get(id).getSource().getCode()).isEqualTo("GET /users");
		assertThat(saved.completed("done")).isTrue();
		assertThat(saved.getState()).isEqualTo(InvestigationRun.State.CANCELLED);
		run.step("read_evidence", id, "read", List.of(id), "read");
		assertThat(report.getInvestigations().get(0).getSteps()).hasSize(1);
		assertThat(restored.markdown()).contains("GET /users", "Which client is used?", id);
		AnalysisReport other = new AnalysisReport();
		other.setFingerprint("other");
		assertThatThrownBy(() -> other.putInvestigation(run)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsInventedToolsOversizedPlansAndRecursiveJson() {
		assertThatThrownBy(() -> InvestigationService.parse("{\"calls\":[{\"operation\":\"shell\",\"target\":\"cmd\"}]}"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InvestigationService.parse("{\"calls\":" + "[".repeat(50) + "0" + "]".repeat(50) + "}"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(InvestigationService.parse("{\"calls\":[],\"questions\":[\"Unknown runtime route\"]}").questions)
				.containsExactly("Unknown runtime route");
	}
}
