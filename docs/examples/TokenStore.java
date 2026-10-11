package demo.auth;

import java.util.prefs.Preferences;

/** Synthetic sample used in README screenshots; not production authentication code. */
public final class TokenStore {
	private final Preferences preferences;

	public TokenStore(Preferences preferences) {
		this.preferences = preferences;
	}

	public void store(String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("Token must not be empty");
		}
		preferences.put("session.token", token);
	}

	public void onLoginSuccess(String token) {
		store(token);
	}

	public void onTokenRefresh(String token) {
		store(token);
	}

	public String restore() {
		return preferences.get("session.token", "");
	}

	public void clear() {
		preferences.remove("session.token");
	}
}
