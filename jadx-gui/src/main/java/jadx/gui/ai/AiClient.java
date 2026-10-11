package jadx.gui.ai;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Model transport has no Swing or project dependencies. */
public interface AiClient {
	String stream(String model, List<Map<String, String>> messages, double temperature, int maxTokens,
			AiCancellation cancellation, Consumer<String> onContent) throws IOException, InterruptedException;
}
