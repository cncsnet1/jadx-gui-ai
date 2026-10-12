package jadx.gui.mcp;

import java.io.IOException;

import com.google.gson.JsonObject;

import jadx.gui.ai.AiCancellation;

interface McpTransport extends AutoCloseable {

	JsonObject exchange(JsonObject message, AiCancellation cancellation) throws IOException, InterruptedException;

	default void version(String version) {
	}

	boolean isClosed();

	@Override
	void close();
}
