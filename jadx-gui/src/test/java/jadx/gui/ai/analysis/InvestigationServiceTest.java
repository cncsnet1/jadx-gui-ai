package jadx.gui.ai.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.fixtures.AnalysisFixture;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationServiceTest {
	private JadxDecompiler open() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile()));
		args.setInlineMethods(false);
		args.setUseDxInput(false);
		JadxDecompiler decompiler = new JadxDecompiler(args);
		decompiler.load();
		return decompiler;
	}

	@Test
	void multipleReadsRetainFactsAndResumeAfterJsonRoundTripWithoutRecollecting() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), ignored -> {
			});
			AnalysisFinding lead = report.getFindings().stream().filter(f -> f.getKind() == AnalysisFinding.Kind.LOGIN
					&& f.getEvidence().get(0).getTitle().contains("submitLogin")).findFirst().orElseThrow();
			AtomicInteger calls = new AtomicInteger();
			List<String> requests = new ArrayList<>();
			AiClient client = (model, messages, temperature, tokens, token, output) -> {
				requests.add(messages.toString());
				int call = calls.incrementAndGet();
				if (call == 1) {
					return "{\"calls\":[{\"operation\":\"trace_calls\",\"target\":\"N1\"}],\"questions\":[\"Runtime authentication?\"]}";
				}
				if (call == 2) {
					return "{\"calls\":[],\"questions\":[\"Runtime authentication?\"]}";
				}
				return "Observed static request path [S1]";
			};
			var run = new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.LOGIN, "Explain path", lead, null,
					new AiProfile(), new AiCancellation(), ignored -> {
					}, ignored -> {
					});
			assertThat(run.getState()).isEqualTo(InvestigationRun.State.NEEDS_EVIDENCE);
			assertThat(run.getSteps()).anyMatch(s -> s.getOperation().equals("trace_calls"));
			assertThat(run.getEvidence().entries()).anyMatch(e -> e.getOperation().equals("trace_calls"));
			assertThat(run.getAnswer()).isNotNull();
			assertThat(calls).hasValue(3);
			assertThat(requests.toString()).contains("fixture-local-secret", "private-query-token");
			var gson = defaultGsonBuilder().create();
			String json = gson.toJson(run);
			assertThat(json).contains("fixture-local-secret", "private-query-token");
			InvestigationRun saved = gson.fromJson(json, InvestigationRun.class);
			assertThat(saved.getEvidence().entries()).anyMatch(e -> e.getSource().getCode().contains("fixture-local-secret")
					&& e.getSource().getCode().contains("private-query-token"));
			int initial = saved.getSteps().size();
			AiClient repeat = (model, messages, temperature, tokens, token, output) -> messages.get(0).get("content").contains("You plan")
					? "{\"calls\":[{\"operation\":\"trace_calls\",\"target\":\"N1\"}],\"questions\":[]}"
					: "Still unverified [S1]";
			var resumed = new InvestigationService(decompiler, repeat).investigate(report, AnalysisGoal.NETWORK, "ignored", null, saved,
					new AiProfile(), new AiCancellation(), ignored -> {
					}, ignored -> {
					});
			assertThat(resumed.getId()).isEqualTo(saved.getId());
			assertThat(resumed.getQuestion()).isEqualTo("Explain path");
			assertThat(resumed.getSteps()).hasSize(initial);
			assertThat(resumed.getState()).isEqualTo(InvestigationRun.State.NO_PROGRESS);
			assertThat(saved.getState()).isEqualTo(InvestigationRun.State.NEEDS_EVIDENCE);
			decompiler.getArgs().setInlineMethods(true);
			assertThatThrownBy(() -> new InvestigationService(decompiler, repeat).investigate(report, AnalysisGoal.LOGIN, "", null, saved,
					new AiProfile(), new AiCancellation(), ignored -> {
					}, ignored -> {
					})).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void cancellationAndFailurePublishPreviouslyReadEvidence() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), ignored -> {
			});
			AtomicReference<InvestigationRun> retained = new AtomicReference<>();
			AiClient cancel = (model, messages, temperature, tokens, token, output) -> {
				token.cancel();
				token.check();
				return "";
			};
			assertThatThrownBy(() -> new InvestigationService(decompiler, cancel).investigate(report, AnalysisGoal.LOGIN, "",
					report.getFindings().get(0),
					null, new AiProfile(), new AiCancellation(), retained::set, ignored -> {
					})).isInstanceOf(InterruptedException.class);
			assertThat(retained.get().getState()).isEqualTo(InvestigationRun.State.CANCELLED);
			assertThat(retained.get().getEvidence().entries()).isNotEmpty();
			AiClient fail = (model, messages, temperature, tokens, token, output) -> {
				throw new java.io.IOException("offline");
			};
			assertThatThrownBy(() -> new InvestigationService(decompiler, fail).investigate(report, AnalysisGoal.LOGIN, "",
					report.getFindings().get(0),
					null, new AiProfile(), new AiCancellation(), retained::set, ignored -> {
					})).isInstanceOf(java.io.IOException.class);
			assertThat(retained.get().getState()).isEqualTo(InvestigationRun.State.FAILED);
			assertThat(retained.get().getEvidence().entries()).isNotEmpty();
		}
	}

	@Test
	void explicitEvidenceReadRevealsTailBeyondPlannerPreview() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), ignored -> {
			});
			AnalysisFinding lead = report.getFindings().get(0);
			InvestigationRun seed = new InvestigationRun("fixture", InvestigationService.profile(decompiler), AnalysisGoal.LOGIN,
					"read tail", lead.getId());
			seed.select(List.of(lead));
			String id = seed.retain("initial", new jadx.gui.ai.AiEvidence(null, "long source", "// ordinary code line\n".repeat(200)
					+ "READ_TAIL_OBSERVED", "hash", false), List.of(), false, java.util.Set.of(lead.getId()));
			AtomicInteger plans = new AtomicInteger();
			AiClient client = (model, messages, temperature, tokens, token, output) -> {
				if (!messages.get(0).get("content").contains("You plan")) {
					return "Tail checked [S1]";
				}
				if (plans.getAndIncrement() == 0) {
					assertThat(messages.toString()).doesNotContain("READ_TAIL_OBSERVED");
					return "{\"calls\":[{\"operation\":\"read_evidence\",\"target\":\"" + id + "\"}]}";
				}
				assertThat(messages.toString()).contains("READ_TAIL_OBSERVED");
				return "{\"calls\":[],\"questions\":[]}";
			};
			var run = new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.LOGIN, "", null, seed,
					new AiProfile(), new AiCancellation(), ignored -> {
					}, ignored -> {
					});
			assertThat(plans).hasValue(2);
			assertThat(run.getState()).isEqualTo(InvestigationRun.State.COMPLETED);
		}
	}

	@Test
	void toolBudgetStopsRepeatedExpansionAndStillProducesAnAnswer() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), ignored -> {
			});
			AnalysisFinding lead = report.getFindings().get(0);
			InvestigationRun seed =
					new InvestigationRun("fixture", InvestigationService.profile(decompiler), AnalysisGoal.LOGIN, "budget", lead.getId());
			seed.select(List.of(lead));
			decompiler.getClasses().get(0).getMethods().stream().limit(4)
					.forEach(m -> seed.register(JadxNodeRef.forMth(m), java.util.Set.of(lead.getId())));
			AtomicInteger rounds = new AtomicInteger();
			AiClient client = (model, messages, temperature, tokens, token, output) -> {
				if (!messages.get(0).get("content").contains("You plan")) {
					return "Budgeted observations [S1]";
				}
				int round = rounds.getAndIncrement();
				var calls = new com.google.gson.JsonArray();
				for (int i = 0; i < 3; i++) {
					int action = round * 3 + i;
					var call = new com.google.gson.JsonObject();
					call.addProperty("operation", List.of("inspect_symbol", "find_references", "trace_calls").get(action / 4));
					call.addProperty("target", "N" + (action % 4 + 1));
					calls.add(call);
				}
				var plan = new com.google.gson.JsonObject();
				plan.add("calls", calls);
				return plan.toString();
			};
			var run = new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.LOGIN, "", null, seed,
					new AiProfile(), new AiCancellation(), ignored -> {
					}, ignored -> {
					});
			assertThat(rounds).hasValue(4);
			assertThat(run.getState()).isEqualTo(InvestigationRun.State.BUDGET);
			assertThat(run.getSteps()).hasSize(12);
			assertThat(run.getAnswer()).isNotNull();
		}
	}

	@Test
	void bytecodeTargetsAndServerUseSameLocalOperation() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			var method = decompiler.getClasses().get(0).getMethods().stream().filter(m -> m.getName().equals("onLoginClick")).findFirst()
					.orElseThrow();
			var targets = new LocalAnalysisTools(decompiler).calls(JadxNodeRef.forMth(method), new AiCancellation());
			assertThat(targets).anyMatch(r -> r.getShortId().startsWith("submitLogin("));
			var args = new com.google.gson.JsonObject();
			args.addProperty("className", method.getDeclaringClass().getRawName());
			args.addProperty("methodId", method.getMethodNode().getMethodInfo().getShortId());
			var result = new jadx.gui.mcp.McpProjectTools(decompiler).call("trace_calls", args);
			assertThat(result.getAsJsonObject("structuredContent").getAsJsonArray("targets").toString()).contains("submitLogin(");
		}
	}
}
