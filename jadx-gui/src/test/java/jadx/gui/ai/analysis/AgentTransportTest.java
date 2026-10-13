package jadx.gui.ai.analysis;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.OpenAiClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTransportTest {
	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 422, 501, 401, 429, 500 })
	void onlyInitialToolRejectionFallsBackAndNeverMasksAuthenticationOrQuota(int code) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		List<String> requests = new ArrayList<>();
		server.createContext("/v1/chat/completions", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(body);
			boolean nativeRequest = body.contains("\"tools\"");
			String response = nativeRequest
					? "{\"error\":\"private-error-content\"}"
					: "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"calls\\\":[],\\\"questions\\\":[]}\"}}]}\n\ndata: [DONE]\n\n";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", nativeRequest ? "application/json" : "text/event-stream");
			exchange.sendResponseHeaders(nativeRequest ? code : 200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		try {
			AiProfile profile = new AiProfile();
			profile.setModel("fixture-model");
			AgentPlanner planner = new AgentPlanner(new OpenAiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", ""),
					profile);
			List<Map<String, String>> context = List.of(Map.of("role", "user", "content", "Investigate"));
			if (List.of(400, 404, 422, 501).contains(code)) {
				assertThat(planner.next(context, new AiCancellation()).finish).isTrue();
				assertThat(planner.isCompatibility()).isTrue();
				assertThat(requests).hasSize(2);
				assertThat(requests.get(1)).doesNotContain("\"tools\"", "private-error-content");
				planner.next(context, new AiCancellation());
				assertThat(requests).hasSize(3);
				assertThat(requests.get(2)).doesNotContain("\"tools\"");
			} else {
				assertThatThrownBy(() -> planner.next(context, new AiCancellation())).isInstanceOf(OpenAiClient.HttpFailure.class);
				assertThat(requests).hasSize(1);
				assertThat(planner.isCompatibility()).isFalse();
			}
		} finally {
			server.stop(0);
		}
	}
}
