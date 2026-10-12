package jadx.gui.ai.fixtures;

public class AnalysisFixture {
	public static final String PASSWORD = "SAMPLE_PRIVATE_PASSWORD_42";
	public static final String URL = "https://api.example.test/login?token=private-query-token";

	public String submitLogin() {
		return send("password", "fixture-local-secret", URL);
	}

	public String onLoginClick() {
		return submitLogin();
	}

	private String send(String field, String value, String address) {
		return address + field + value;
	}
}
