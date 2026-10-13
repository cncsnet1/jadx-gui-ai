package jadx.gui.ai.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.fixtures.AnalysisFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentInvestigationTest {
	private JadxDecompiler open() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile()));
		args.setUseDxInput(false);
		args.setInlineMethods(false);
		JadxDecompiler decompiler = new JadxDecompiler(args);
		decompiler.load();
		return decompiler;
	}

	@Test
	void nativeAgentUsesToolResultsThenResumesWithFollowUpAndPersistedPlan() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), s -> {
			});
			AnalysisFinding lead = report.getFindings().get(0);
			InvestigationRun seed = new InvestigationRun("fixture", InvestigationService.profile(decompiler), AnalysisGoal.LOGIN,
					"Trace login", lead.getId());
			seed.select(List.of(lead));
			String evidence =
					seed.retain("initial", new AiEvidence(null, "source", "password=\"private-raw-value\";\nretainedMarker", "h", false),
							List.of(), false, Set.of(lead.getId()));
			AtomicInteger turns = new AtomicInteger();
			NativeClient client = new NativeClient(messages -> {
				assertThat(messages.toString()).doesNotContain("private-raw-value");
				if (turns.getAndIncrement() == 0) {
					return reply(tool("p1", "update_plan", plan("active", List.of())),
							tool("r1", "read_evidence", Map.of("target", evidence)));
				}
				assertThat(messages.toString()).contains("tool_call_id", "r1", "retainedMarker", "Public plan saved");
				JsonObject previous = messages.get(2).getAsJsonObject();
				String arguments = previous.getAsJsonArray("tool_calls").get(1).getAsJsonObject()
						.getAsJsonObject("function").get("arguments").getAsString();
				assertThat(JsonParser.parseString(arguments).getAsJsonObject().get("target").getAsString()).isEqualTo(evidence);
				return reply(tool("p2", "update_plan", plan("done", List.of(evidence))),
						tool("f2", "finish_investigation", Map.of("questions", List.of())));
			});
			InvestigationRun run = new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.LOGIN, "", null, seed,
					new AiProfile(), new AiCancellation(), s -> {
					}, s -> {
					});
			assertThat(run.getState()).isEqualTo(InvestigationRun.State.COMPLETED);
			assertThat(run.getCalls()).isEqualTo(1);
			assertThat(run.getRounds()).isEqualTo(2);
			assertThat(run.getTasks().get(0).getEvidence()).containsExactly(evidence);
			assertThat(run.getAnswer()).isNotNull();
			assertThat(seed.getTasks()).isEmpty();
			InvestigationRun interrupted = run.snapshot();
			interrupted.begin("Trace a new direction");
			assertThat(interrupted.getAnsweredFollowUps()).isLessThan(interrupted.getFollowUps().size());
			report.putInvestigation(interrupted);
			assertThat(report.markdown()).contains(jadx.gui.ai.AiText.str("agent.answer_previous"));
			var gson = jadx.core.utils.GsonUtils.defaultGsonBuilder().create();
			InvestigationRun restored = gson.fromJson(gson.toJson(run), InvestigationRun.class);
			NativeClient resume = new NativeClient(messages -> {
				assertThat(messages.toString()).contains("Trace session reuse next", "retainedMarker");
				return reply(tool("f3", "finish_investigation", Map.of("questions", List.of("Need runtime session evidence"))));
			});
			var continued = new InvestigationService(decompiler, resume).investigate(report, AnalysisGoal.NETWORK, "ignored", null,
					restored, "Trace session reuse next", new AiProfile(), new AiCancellation(), s -> {
					}, s -> {
					});
			assertThat(continued.getId()).isEqualTo(run.getId());
			assertThat(continued.getFollowUps()).containsExactly("Trace session reuse next");
			assertThat(continued.getSteps()).hasSize(run.getSteps().size());
			assertThat(continued.getState()).isEqualTo(InvestigationRun.State.NEEDS_EVIDENCE);
			assertThat(resume.finalPrompt).contains("Trace session reuse next");
			assertThat(restored.getFollowUps()).isEmpty();
			report.putInvestigation(continued);
			assertThat(report.markdown()).contains("Trace session reuse next");
		}
	}

	@Test
	void agentRecoversFromBadToolTargetAndDoesNotTreatBlockedPlanAsComplete() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), s -> {
			});
			AtomicInteger turns = new AtomicInteger();
			NativeClient client = new NativeClient(messages -> {
				int turn = turns.getAndIncrement();
				if (turn == 0) {
					return reply(tool("bad", "inspect_symbol", Map.of("target", "N99999")));
				}
				if (turn == 1) {
					assertThat(messages.toString()).contains("Tool error", "Replan");
					return reply(tool("good", "search_artifacts", Map.of("query", "login")));
				}
				return reply(tool("plan", "update_plan", plan("blocked", List.of())),
						tool("finish", "finish_investigation", Map.of("questions", List.of())));
			});
			var run = new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.LOGIN, "", report.getFindings().get(0),
					null, new AiProfile(), new AiCancellation(), s -> {
					}, s -> {
					});
			assertThat(run.getState()).isEqualTo(InvestigationRun.State.NEEDS_EVIDENCE);
			assertThat(run.getCalls()).isEqualTo(2);
			assertThat(run.getSteps()).anyMatch(s -> s.getOperation().equals("search_artifacts") && !s.getEvidence().isEmpty());
			assertThat(run.getEvidence().entries()).noneMatch(e -> e.getSource().getTitle().contains("N99999"));
		}
	}

	@Test
	void invalidPlanCannotExecuteItsReadsAndCancellationKeepsEvidence() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), s -> {
			});
			AtomicReference<InvestigationRun> saved = new AtomicReference<>();
			AtomicInteger turns = new AtomicInteger();
			AiCancellation cancellation = new AiCancellation();
			NativeClient client = new NativeClient(messages -> {
				if (turns.getAndIncrement() == 0) {
					return reply(tool("badplan", "update_plan", plan("done", List.of("E-invented"))),
							tool("read", "search_artifacts", Map.of("query", "login")));
				}
				assertThat(messages.toString()).contains("Invalid tool request or plan", "Not executed");
				cancellation.cancel();
				return reply(tool("finish", "finish_investigation", Map.of("questions", List.of())));
			});
			assertThatThrownBy(() -> new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.LOGIN, "",
					report.getFindings().get(0), null, new AiProfile(), cancellation, saved::set, s -> {
					})).isInstanceOf(InterruptedException.class);
			assertThat(saved.get().getState()).isEqualTo(InvestigationRun.State.CANCELLED);
			assertThat(saved.get().getTasks()).isEmpty();
			assertThat(saved.get().getEvidence().entries()).isNotEmpty().noneMatch(e -> e.getOperation().equals("search_artifacts"));
		}
	}

	@Test
	void protocolRejectsInventedOperationsDuplicateIdsFinishWithReadsAndDeepArguments() {
		List<JsonObject> invalid = List.of(
				reply(tool("x", "shell", Map.of("target", "cmd"))),
				reply(tool("x", "read_evidence", Map.of("target", "E1")), tool("x", "read_evidence", Map.of("target", "E2"))),
				reply(tool("x", "finish_investigation", Map.of("questions", List.of())),
						tool("y", "read_evidence", Map.of("target", "E1"))),
				reply(tool("x", "update_plan", plan("done", List.of()))));
		for (JsonObject response : invalid) {
			AgentPlanner planner = new AgentPlanner(new NativeClient(messages -> response), new AiProfile());
			assertThatThrownBy(() -> planner.next(List.of(Map.of("role", "user", "content", "goal")), new AiCancellation()))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> InvestigationService.checkedObject("{\"x\":" + "[".repeat(50) + "0" + "]".repeat(50) + "}"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void oldRecordsGetEmptyAgentStateAndFollowUpsAreBoundedAndRedacted() {
		var run = new Gson().fromJson("{\"question\":\"old\",\"goal\":\"LOGIN\"}", InvestigationRun.class);
		assertThat(run.getTasks()).isEmpty();
		assertThat(run.getFollowUps()).isEmpty();
		run.begin("password=\"private-value\"");
		assertThat(run.getFollowUps().toString()).doesNotContain("private-value");
		for (int i = 0; i < 11; i++) {
			run.begin("next " + i);
		}
		assertThatThrownBy(() -> run.begin("one more")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> run.begin("x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
	}

	private static Map<String, Object> plan(String status, List<String> evidence) {
		return Map.of("tasks", List.of(Map.of("title", "Trace authentication", "status", status, "evidence", evidence)),
				"note", "Checking retained code");
	}

	private static JsonObject tool(String id, String name, Map<String, ?> args) {
		return JsonParser.parseString(new Gson().toJson(Map.of("id", id, "type", "function",
				"function", Map.of("name", name, "arguments", new Gson().toJson(args))))).getAsJsonObject();
	}

	private static JsonObject reply(JsonObject... calls) {
		JsonObject reply = new JsonObject();
		reply.addProperty("role", "assistant");
		JsonArray array = new JsonArray();
		for (JsonObject call : calls) {
			array.add(call);
		}
		reply.add("tool_calls", array);
		return reply;
	}

	private static final class NativeClient implements AiClient {
		private final Function<JsonArray, JsonObject> respond;
		private String finalPrompt;

		NativeClient(Function<JsonArray, JsonObject> respond) {
			this.respond = respond;
		}

		@Override
		public boolean supportsTools() {
			return true;
		}

		@Override
		public JsonObject completeWithTools(String model, JsonArray messages, JsonArray tools, int maxTokens, AiCancellation token) {
			assertThat(tools).hasSize(8);
			return respond.apply(messages);
		}

		@Override
		public String stream(String model, List<Map<String, String>> messages, double temperature, int maxTokens,
				AiCancellation token, Consumer<String> content) {
			finalPrompt = messages.toString();
			return "Observed source [S1]. Runtime behavior remains unverified.";
		}
	}
}
