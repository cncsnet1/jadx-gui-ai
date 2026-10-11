package jadx.gui.ai;

import java.util.UUID;
import java.util.function.Function;

/** One saved connection. Environment credentials are resolved only when a request is made. */
public final class AiProfile {
	private String id = UUID.randomUUID().toString();
	private String name = "";
	private String url = "";
	private String apiKey = "";
	private String keyEnvironment = "";
	private String model = "";
	private int outputTokens = 4096;

	public AiProfile() {
	}

	public AiProfile copy() {
		AiProfile copy = new AiProfile();
		copy.id = id;
		copy.name = name;
		copy.url = url;
		copy.apiKey = apiKey;
		copy.keyEnvironment = keyEnvironment;
		copy.model = model;
		copy.outputTokens = outputTokens;
		return copy;
	}

	public AiProfile duplicate() {
		AiProfile copy = copy();
		copy.id = UUID.randomUUID().toString();
		return copy;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return value(name);
	}

	public void setName(String name) {
		this.name = name.trim();
	}

	public String getUrl() {
		return value(url);
	}

	public void setUrl(String url) {
		this.url = url.trim();
	}

	public String getApiKey() {
		return value(apiKey);
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getKeyEnvironment() {
		return value(keyEnvironment);
	}

	public void setKeyEnvironment(String keyEnvironment) {
		this.keyEnvironment = keyEnvironment.trim();
	}

	public String getModel() {
		return value(model);
	}

	public void setModel(String model) {
		this.model = model.trim();
	}

	public int getOutputTokens() {
		return Math.max(256, Math.min(32768, outputTokens));
	}

	public void setOutputTokens(int outputTokens) {
		this.outputTokens = outputTokens;
	}

	public String resolveApiKey() {
		return resolveApiKey(System::getenv);
	}

	String resolveApiKey(Function<String, String> environment) {
		if (getKeyEnvironment().isEmpty()) {
			return getApiKey();
		}
		String key = environment.apply(getKeyEnvironment());
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException(AiText.str("env_missing") + getKeyEnvironment());
		}
		return key;
	}

	public void validate() {
		if (getName().isBlank()) {
			throw new IllegalArgumentException(AiText.str("name_required"));
		}
		OpenAiClient.endpoint(getUrl());
		if (getModel().isBlank()) {
			throw new IllegalArgumentException(AiText.str("model_required"));
		}
		if (!getKeyEnvironment().isEmpty() && !getKeyEnvironment().matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException(AiText.str("env_invalid"));
		}
	}

	public String destination() {
		try {
			java.net.URI endpoint = OpenAiClient.endpoint(getUrl());
			return endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
		} catch (IllegalArgumentException e) {
			return AiText.str("not_configured");
		}
	}

	private static String value(String value) {
		return value == null ? "" : value;
	}

	@Override
	public String toString() {
		return getName();
	}
}
