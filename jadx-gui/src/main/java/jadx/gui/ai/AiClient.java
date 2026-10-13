package jadx.gui.ai;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Model transport has no Swing or project dependencies. */
public interface AiClient {
	String stream(String model, List<Map<String, String>> messages, double temperature, int maxTokens,
			AiCancellation cancellation, Consumer<String> onContent) throws IOException, InterruptedException;

	default boolean supportsTools() {
		return false;
	}

	default JsonObject completeWithTools(String model, JsonArray messages, JsonArray tools, int maxTokens,
			AiCancellation cancellation) throws IOException, InterruptedException {
		throw new UnsupportedOperationException("Tool transport unavailable");
	}
}
