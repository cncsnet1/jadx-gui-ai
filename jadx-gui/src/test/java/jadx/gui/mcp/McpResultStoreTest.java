package jadx.gui.mcp;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpResultStoreTest {
	@Test
	void longStructuredResultsCanBeReassembledWithoutBrokenJson() throws Exception {
		JsonObject payload = new JsonObject();
		payload.addProperty("code", "代码🙂\\\"\n".repeat(8000));
		McpResultStore store = new McpResultStore();
		JsonObject page = McpJson.object(store.retain(payload));
		String id = page.get("resultId").getAsString();
		StringBuilder result = new StringBuilder();
		while (true) {
			result.append(page.get("fragment").getAsString());
			if (!page.has("nextOffset")) {
				break;
			}
			page = McpJson.object(store.read(id, page.get("nextOffset").getAsInt()));
		}
		assertThat(McpJson.object(result.toString())).isEqualTo(payload);
		assertThatThrownBy(() -> new McpResultStore().read(id, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.read(id, -1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void reaProfileUsesOnlyAdvertisedSelectedToolsAndKeepsSchemas() throws Exception {
		var known = McpJson.object(
				"{\"name\":\"binary_session\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"expected_package_version\":{\"type\":\"string\"}}}}");
		var unrelated = McpJson.object("{\"name\":\"capture_process_scenario\",\"inputSchema\":{\"type\":\"object\"}}");
		assertThat(McpToolProfiles.select("rea-static", List.of(known, unrelated))).containsExactly(known);
		assertThat(McpToolProfiles.select("all", List.of(known, unrelated))).hasSize(2);
	}
}
