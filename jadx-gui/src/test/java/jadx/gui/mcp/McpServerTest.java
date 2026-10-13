package jadx.gui.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.gui.ai.fixtures.CallerFixture;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerTest {

	private JadxDecompiler decompiler;
	private McpProjectTools tools;
	private McpServer server;
	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void open() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(Path.of(CallerFixture.class.getResource("CallerFixture.class").toURI()).toFile(),
				Path.of(CallerFixture.Inner.class.getResource("CallerFixture$Inner.class").toURI()).toFile()));
		args.setInlineMethods(false);
		decompiler = new JadxDecompiler(args);
		decompiler.load();
		tools = new McpProjectTools(decompiler);
		server = new McpServer(tools, 0);
	}

	@AfterEach
	void close() {
		server.close();
		decompiler.close();
	}

	@Test
	void authenticatesOriginAndProtocolBeforeAccess() throws Exception {
		String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
		assertThat(post(request, null, null, null).statusCode()).isEqualTo(401);
		assertThat(post(request, "wrong", null, null).statusCode()).isEqualTo(401);
		assertThat(post(request, server.token(), "https://attacker.invalid", null).statusCode()).isEqualTo(403);
		assertThat(post(request, server.token(), "null", null).statusCode()).isEqualTo(403);
		assertThat(post(request, server.token(), null, "unknown").statusCode()).isEqualTo(400);
		HttpResponse<String> response = post(request, server.token(), null, McpJson.VERSION);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools"))
				.hasSize(9);
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
	}

	@Test
	void negotiatesLegacyCompatibleVersionAndAcceptsNotifications() throws Exception {
		HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}", server.token(), null, null);
		assertThat(response.body()).contains("2025-06-18", "\"tools\":{}", "\"id\":\"init\"");
		assertThat(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", server.token(), null, McpJson.VERSION)
				.statusCode()).isEqualTo(202);
		assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"not_supported\"}", server.token(), null, null).body())
				.contains("-32601");
		assertThat(post("[]", server.token(), null, null).statusCode()).isEqualTo(400);
		assertThat(post("x".repeat(65_537), server.token(), null, null).statusCode()).isEqualTo(413);
		HttpRequest get = HttpRequest.newBuilder(URI.create(server.endpoint())).header("Authorization", "Bearer " + server.token())
				.GET().build();
		assertThat(http.send(get, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
	}

	@Test
	void readsRealProjectSourceMethodsAndUsages() throws Exception {
		JsonObject project = data("get_project", "{}");
		assertThat(project.getAsJsonArray("files").toString()).contains("CallerFixture.class").doesNotContain("E:", "C:");
		JsonObject classes = data("list_classes", "{\"query\":\"CallerFixture\",\"limit\":1}");
		assertThat(classes.getAsJsonArray("classes")).hasSize(1);
		assertThat(classes.get("nextOffset").getAsInt()).isEqualTo(1);
		String className = CallerFixture.class.getName();
		JsonObject args = new JsonObject();
		args.addProperty("className", className);
		JsonObject methods = data("list_methods", args.toString());
		String method = methods.getAsJsonArray("methods").asList().stream().map(item -> item.getAsJsonObject())
				.filter(item -> item.get("methodId").getAsString().startsWith("store("))
				.findFirst().orElseThrow().get("methodId").getAsString();
		args.addProperty("methodId", method);
		assertThat(data("get_method_code", args.toString()).get("code").getAsString()).contains("store", "saved");
		assertThat(data("find_usages", args.toString()).getAsJsonArray("usages").toString())
				.contains(".first", ".second", ".third", ".fourth");
		args.remove("methodId");
		args.addProperty("limit", 15);
		JsonObject code = data("get_class_code", args.toString());
		assertThat(code.get("code").getAsString()).hasSize(15);
		assertThat(code.get("nextOffset").getAsInt()).isEqualTo(15);
		assertThat(data("search_code", "{\"query\":\"saved\"}").getAsJsonArray("matches").toString()).contains(className);
	}

	@Test
	void rejectsInvalidArgumentsAndRevokedProjectsWithoutLeakingSource() throws Exception {
		assertThat(tools.call("list_classes", McpJson.object("{\"offset\":-1}")).get("isError").getAsBoolean()).isTrue();
		assertThat(tools.call("list_classes", McpJson.object("{\"limit\":1.5}")).get("isError").getAsBoolean()).isTrue();
		assertThat(tools.call("get_class_code", McpJson.object("{\"className\":\"missing\"}")).get("isError").getAsBoolean()).isTrue();
		assertThat(tools.call("get_project", McpJson.object("{\"path\":\"private\"}")).get("isError").getAsBoolean()).isTrue();
		assertThat(data("list_classes", "{\"offset\":2147483647}").getAsJsonArray("classes")).isEmpty();
		tools.revoke();
		JsonObject revoked = tools.call("get_project", new JsonObject());
		assertThat(revoked.get("isError").getAsBoolean()).isTrue();
		assertThat(revoked.toString()).doesNotContain("CallerFixture");
	}

	@Test
	void stoppingSharingRotatesCredentialsAndProjectState() throws Exception {
		try (McpManager manager = new McpManager()) {
			manager.projectLoaded(true);
			manager.startServer(decompiler, 0);
			String first = manager.server().token();
			manager.projectLoaded(false);
			assertThat(manager.server()).isNull();
			assertThat(manager.isProjectReady()).isFalse();
			manager.startServer(decompiler, 0);
			assertThat(manager.server().token()).isNotEqualTo(first);
		}
	}

	private JsonObject data(String name, String args) throws Exception {
		String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ name + "\",\"arguments\":" + args + "}}";
		JsonObject result = McpJson.object(post(request, server.token(), null, McpJson.VERSION).body()).getAsJsonObject("result");
		assertThat(result.get("isError").getAsBoolean()).isFalse();
		return result.getAsJsonObject("structuredContent");
	}

	private HttpResponse<String> post(String body, String token, String origin, String version) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.endpoint())).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (origin != null) {
			request.header("Origin", origin);
		}
		if (version != null) {
			request.header("MCP-Protocol-Version", version);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
