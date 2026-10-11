package jadx.gui.ai;

import java.net.ConnectException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/** Actionable diagnostics without echoing provider bodies or credentials. */
public final class AiConnectionError {
	private AiConnectionError() {
	}

	public static String describe(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof OpenAiClient.HttpFailure) {
				int status = ((OpenAiClient.HttpFailure) cause).getStatus();
				String key = status == 401 || status == 403 ? "error_auth"
						: status == 404 ? "error_endpoint" : status == 429 ? "error_quota"
						: status >= 500 ? "error_provider" : "error_request";
				return "HTTP " + status + " · " + AiText.str(key);
			}
			if (cause instanceof SSLException) {
				return AiText.str("error_tls");
			}
			if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
				return AiText.str("error_network");
			}
		}
		String message = error.getMessage();
		if (message != null && message.contains("timed out")) {
			return AiText.str("error_timeout");
		}
		return message == null ? AiText.str("error_request") : message;
	}
}
