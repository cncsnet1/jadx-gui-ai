package jadx.gui.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jadx.api.JadxDecompiler;

/** Runtime state is deliberately separate from saved settings: nothing auto-starts. */
public final class McpManager implements AutoCloseable {

	private final Map<String, McpClient> clients = new LinkedHashMap<>();
	private McpServer server;
	private volatile boolean projectReady;
	private Runnable changeListener = () -> { };

	public synchronized void setChangeListener(Runnable listener) {
		changeListener = listener;
	}

	public boolean isProjectReady() {
		return projectReady;
	}

	public synchronized void projectLoaded(boolean loaded) {
		projectReady = loaded;
		if (!loaded) {
			stopServer();
		}
	}

	public synchronized void startServer(JadxDecompiler decompiler, int port) throws IOException {
		stopServer();
		server = new McpServer(new McpProjectTools(decompiler), port);
		changeListener.run();
	}

	public synchronized McpServer server() {
		return server;
	}

	public synchronized void stopServer() {
		if (server != null) {
			server.close();
			server = null;
			changeListener.run();
		}
	}

	public synchronized void add(McpClient client) {
		disconnect(client.configuration().id);
		clients.put(client.configuration().id, client);
		changeListener.run();
	}

	public synchronized McpClient client(String id) {
		return clients.get(id);
	}

	public synchronized List<McpClient> clients() {
		return new ArrayList<>(clients.values());
	}

	public synchronized void disconnect(String id) {
		McpClient client = clients.remove(id);
		if (client != null) {
			client.close();
			changeListener.run();
		}
	}

	@Override
	public synchronized void close() {
		stopServer();
		clients.values().forEach(McpClient::close);
		clients.clear();
		projectReady = false;
		changeListener.run();
	}
}
