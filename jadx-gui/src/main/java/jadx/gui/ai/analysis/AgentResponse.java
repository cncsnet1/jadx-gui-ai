package jadx.gui.ai.analysis;

import java.io.IOException;
import java.util.function.Consumer;

import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiText;
import jadx.gui.ai.OpenAiClient.OutputLimitException;

/**
 * Retries buffered task responses only: truncated text and tool calls are never executed or saved.
 */
final class AgentResponse {

	@FunctionalInterface
	interface Request<T> {
		T send(int outputTokens) throws IOException, InterruptedException;
	}

	private int budget;
	private final Consumer<String> status;

	AgentResponse(int configuredTokens, Consumer<String> status) {
		budget = Math.min(32_768, Math.max(4096, configuredTokens));
		this.status = status;
	}

	<T> T read(AiCancellation token, Request<T> request) throws IOException, InterruptedException {
		token.check();
		try {
			return request.send(budget);
		} catch (OutputLimitException e) {
			token.check();
			if (budget >= 32_768) {
				throw e;
			}
			budget = Math.min(32_768, Math.max(16_384, budget * 2));
			status.accept(AiText.str("agent.retry_output"));
			token.check();
			return request.send(budget);
		}
	}
}
