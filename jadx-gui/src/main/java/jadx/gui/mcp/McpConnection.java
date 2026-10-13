package jadx.gui.mcp;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saved launch configuration. Secrets are referenced by environment variable, never copied into
 * settings.
 */
public final class McpConnection {

	public String id = UUID.randomUUID().toString();
	public String name = "MCP";
	public String transport = "stdio";
	public String command = "";
	public List<String> args = new ArrayList<>();
	public Map<String, String> environment = new LinkedHashMap<>();
	public String url = "";
	public String bearerEnvironment = "";
	public String toolProfile = "all";

	public McpConnection copy() {
		McpConnection copy = new McpConnection();
		copy.id = id;
		copy.name = name;
		copy.transport = transport;
		copy.command = command;
		copy.args = args == null ? new ArrayList<>() : new ArrayList<>(args);
		copy.environment = environment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(environment);
		copy.url = url;
		copy.bearerEnvironment = bearerEnvironment;
		copy.toolProfile = toolProfile;
		return copy;
	}

	public void validate() {
		if (!"all".equals(toolProfile) && !"rea-static".equals(toolProfile) && !"burp-readonly".equals(toolProfile)) {
			throw new IllegalArgumentException("Unknown MCP tool profile");
		}
		if (id == null || id.isBlank() || name == null || name.isBlank() || name.length() > 100) {
			throw new IllegalArgumentException("MCP connection needs a name");
		}
		if ("stdio".equals(transport)) {
			if (command == null || command.isBlank() || args == null || args.size() > 100
					|| args.stream().anyMatch(java.util.Objects::isNull)) {
				throw new IllegalArgumentException("An executable and a JSON array of arguments are required");
			}
			if (environment == null || environment.size() > 50) {
				throw new IllegalArgumentException("Invalid environment mapping");
			}
			for (Map.Entry<String, String> entry : environment.entrySet()) {
				if (!variable(entry.getKey()) || !variable(entry.getValue())) {
					throw new IllegalArgumentException("Environment maps child variable names to existing host variable names");
				}
			}
		} else if ("http".equals(transport) || "sse".equals(transport)) {
			URI uri = URI.create(url);
			boolean local = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
			if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
					|| uri.getPort() == 0 || uri.getPort() > 65535
					|| !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))) {
				throw new IllegalArgumentException("Use HTTPS, or HTTP on localhost; no URL credentials, query or fragment");
			}
			if (bearerEnvironment == null || (!bearerEnvironment.isEmpty() && !variable(bearerEnvironment))) {
				throw new IllegalArgumentException("Invalid bearer token environment variable");
			}
		} else {
			throw new IllegalArgumentException("Use stdio, http or sse transport");
		}
	}

	private static boolean variable(String name) {
		return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
	}

	@Override
	public String toString() {
		return name + " · " + transport;
	}
}
