package jadx.gui.ai.analysis;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.AiText;
import jadx.gui.ai.OpenAiClient;
import jadx.gui.ai.OpenAiClient.OutputLimitException;
import jadx.gui.ai.fixtures.AnalysisFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRecoveryTest {

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void nativeTruncationRetainsInitialEvidenceAndEitherRecoversOrStopsAfterOneRetry(boolean exhausted) throws Exception {
		Gson gson = new Gson();
		String opaque = "provider-continuation-".repeat(2000);
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		AtomicInteger planning = new AtomicInteger();
		AtomicInteger summaries = new AtomicInteger();
		AtomicInteger initialEvidence = new AtomicInteger();
		AtomicReference<InvestigationRun> published = new AtomicReference<>();
		List<String> statuses = new ArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			try {
				JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
						.getAsJsonObject();
				requests.add(request);
				String response;
				if (request.has("tools")) {
					int turn = planning.incrementAndGet();
					if (turn == 1) {
						initialEvidence.set(published.get().getEvidence().entries().size());
					}
					Object calls = turn <= 2
							? List.of(tool("read", "search_artifacts", Map.of("query", "login")))
							: List.of(tool("finish", "finish_investigation", Map.of("questions", List.of("Runtime evidence needed"))));
					response =
							gson.toJson(Map.of("choices", List.of(Map.of("finish_reason", exhausted || turn == 1 ? "length" : "tool_calls",
									"message", Map.of("role", "assistant", "reasoning_content", opaque, "tool_calls", calls)))));
				} else {
					int turn = summaries.incrementAndGet();
					response = "data: " + gson.toJson(Map.of("choices", List.of(Map.of("delta",
							Map.of("content", turn == 1 ? "discarded-partial" : "Observed evidence [S1]."),
							"finish_reason", turn == 1 ? "length" : "stop")))) + "\n\n";
				}
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
			} finally {
				exchange.close();
			}
		});
		server.start();
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), s -> {
			});
			AiProfile profile = new AiProfile();
			profile.setModel("fixture");
			profile.setOutputTokens(256);
			var service = new InvestigationService(decompiler,
					new OpenAiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", ""));
			if (exhausted) {
				assertThatThrownBy(() -> service.investigate(report, AnalysisGoal.LOGIN, "Trace login", null, null, profile,
						new AiCancellation(), published::set, statuses::add)).isInstanceOf(OutputLimitException.class);
				assertThat(planning.get()).isEqualTo(2);
				assertThat(summaries.get()).isZero();
				assertThat(published.get().getState()).isEqualTo(InvestigationRun.State.FAILED);
				assertThat(published.get().getReason()).isEqualTo(AiText.str("error_output_limit"));
				assertThat(published.get().getCalls()).isZero();
				assertThat(published.get().getAnswer()).isNull();
			} else {
				var run = service.investigate(report, AnalysisGoal.LOGIN, "Trace login", null, null, profile,
						new AiCancellation(), published::set, statuses::add);
				assertThat(run.getState()).isEqualTo(InvestigationRun.State.NEEDS_EVIDENCE);
				assertThat(run.getCalls()).isEqualTo(1);
				assertThat(run.getRounds()).isEqualTo(2);
				assertThat(planning.get()).isEqualTo(3);
				assertThat(summaries.get()).isEqualTo(2);
				assertThat(requests.get(2).getAsJsonArray("messages").get(2).getAsJsonObject().get("reasoning_content").getAsString())
						.isEqualTo(opaque);
				assertThat(requests.get(2).get("max_tokens").getAsInt()).isEqualTo(16_384);
				assertThat(requests.get(3).toString()).doesNotContain("provider-continuation-");
				report.putInvestigation(run);
				assertThat(report.markdown()).contains("Observed evidence").doesNotContain("discarded-partial", "provider-continuation-");
			}
			assertThat(initialEvidence.get()).isPositive();
			assertThat(published.get().getEvidence().entries()).isNotEmpty();
			assertThat(gson.toJson(published.get())).doesNotContain("provider-continuation-", "discarded-partial");
			assertThat(requests.get(0).has("tools")).isTrue(); // No tiny preflight selection request.
			assertThat(requests.get(0).get("max_tokens").getAsInt()).isEqualTo(4096);
			assertThat(requests.get(1).get("max_tokens").getAsInt()).isEqualTo(16_384);
			assertThat(statuses).contains(AiText.str("agent.retry_output"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void compatibilityPlannerRetriesTruncationBeforeParsingAnyCalls() throws Exception {
		List<Integer> budgets = new ArrayList<>();
		AiClient client = (model, messages, temperature, budget, token, content) -> {
			budgets.add(budget);
			if (budgets.size() == 1) {
				content.accept("{\"calls\":[");
				throw new OutputLimitException();
			}
			return "{\"calls\":[],\"questions\":[]}";
		};
		var planner = new AgentPlanner(client, new AiProfile());
		assertThat(planner.next(List.of(Map.of("role", "user", "content", "Investigate")), new AiCancellation()).finish).isTrue();
		assertThat(budgets).containsExactly(4096, 16_384);
	}

	@Test
	void retriesAreBoundedAndCancellationAndUnrelatedErrorsAreNotRetried() {
		AtomicInteger calls = new AtomicInteger();
		AgentResponse response = new AgentResponse(32_768, s -> {
		});
		assertThatThrownBy(() -> response.read(new AiCancellation(), budget -> {
			calls.incrementAndGet();
			throw new OutputLimitException();
		})).isInstanceOf(OutputLimitException.class);
		assertThat(calls.get()).isEqualTo(1);
		AiCancellation token = new AiCancellation();
		assertThatThrownBy(() -> new AgentResponse(4096, s -> token.cancel()).read(token, budget -> {
			calls.incrementAndGet();
			throw new OutputLimitException();
		})).isInstanceOf(InterruptedException.class);
		assertThat(calls.get()).isEqualTo(2);
		assertThatThrownBy(() -> new AgentResponse(4096, s -> {
		}).read(new AiCancellation(), budget -> {
			calls.incrementAndGet();
			throw new IOException("connection closed");
		})).isInstanceOf(IOException.class).hasMessage("connection closed");
		assertThat(calls.get()).isEqualTo(3);
	}

	private static Map<String, Object> tool(String id, String name, Object arguments) {
		return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", new Gson().toJson(arguments)));
	}

	private static JadxDecompiler open() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile()));
		args.setUseDxInput(false);
		JadxDecompiler decompiler = new JadxDecompiler(args);
		decompiler.load();
		return decompiler;
	}
}
