package jadx.gui.mcp;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;

/** Tool selection only: presence is not proof of provider readiness or permission to invoke. */
public final class McpToolProfiles {
	private static final Set<String> REA = Set.of("binary_session", "open_binary", "close_binary", "binary_overview",
			"analyze_function", "get_call_graph", "find_xrefs_to_name", "build_call_path", "trace_call_path",
			"inspect_native_api", "inspect_analysis_view", "get_evidence_bundle", "get_navigation_context", "inspect_address_context");

	private McpToolProfiles() {
	}

	public static List<JsonObject> select(String profile, List<JsonObject> catalog) {
		if (!"rea-static".equals(profile)) {
			return catalog;
		}
		return catalog.stream().filter(t -> REA.contains(McpJson.string(t, "name", ""))).collect(Collectors.toList());
	}
}
