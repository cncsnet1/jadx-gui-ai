package jadx.gui.ai.analysis;

import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;
import jadx.gui.settings.data.ProjectData;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRulesTest {
	@Test
	void cryptoSummaryRetainsAlgorithmsAndLiteralValuesWithoutClaimingArgumentBindings() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.behavior("Landroid/util/Log;.d\n".repeat(150) + "Ljavax/crypto/Cipher;.getInstance\n"
				+ "Ljavax/crypto/spec/IvParameterSpec;.<init>\n",
				"const @1 = \"AES/GCM/NoPadding\"\n"
						+ "const @2 = \"opaque-private-key-91745\"\n",
				"CipherTask", JadxNodeRef.forCls("CipherTask"));
		assertThat(report.getFindings()).hasSize(1);
		assertThat(report.getFindings().get(0).getEvidence().get(0).getCode())
				.contains("AES/GCM/NoPadding", "IvParameterSpec", "not established", "opaque-private-key-91745")
				.doesNotContain("android/util/Log");
		rules.behavior("Ljava/security/MessageDigest;.getInstance", "const @0 = \"SHA-256\"", "DigestTask", null);
		assertThat(report.getFindings()).extracting(AnalysisFinding::getKind).containsExactly(Kind.CRYPTO, Kind.CRYPTO);
	}

	@Test
	void shortBearerValuesAndRelativeRouteQueriesRemainVisibleAndPersisted() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.scan("Send Bearer sample-token123 with requests", "README.txt", null);
		String route = "retrofit2/http/GET /v1/login?token=private-value#fragment";
		rules.add(Kind.HTTP_ROUTE, route, "Api.login", JadxNodeRef.forCls("Api"), route);
		assertThat(report.getFindings()).hasSize(2);
		assertThat(report.getFindings().get(0).getRawValue()).isEqualTo("Bearer sample-token123");
		assertThat(report.getFindings().get(1).getValue()).isEqualTo(route);
		assertThat(report.markdown()).contains("sample-token123", "private-value", "fragment");
		assertThat(defaultGsonBuilder().create().toJson(report)).contains("sample-token123", "private-value", "fragment");
	}

	@Test
	void bareUrlsRetainHostPathCredentialsAndQueryValues() {
		String url = "https://user:password@api.example.test/v1?token=private#fragment";
		AnalysisReport report = new AnalysisReport();
		new AnalysisRules(report).scan(url, "Client", null);
		assertThat(report.getFindings().get(0).getValue()).isEqualTo(url);
		assertThat(report.getFindings().get(0).getEvidence().get(0).getCode()).isEqualTo(url);
		new AnalysisRules(report).scan("password=private\nclient_secret: private", "config.properties", null);
		assertThat(report.getFindings()).anyMatch(f -> f.getKind() == Kind.CREDENTIAL && f.getValue().equals("private"));
	}

	@Test
	void mixedCaseUrlCredentialsArePreservedInSnapshotsAndExports() {
		AnalysisReport report = new AnalysisReport();
		new AnalysisRules(report).scan("HTTPS://alice:pass-one@api.example.test/login?token=private-value", "Config", null);
		String value = report.getFindings().get(0).getValue();
		assertThat(value).isEqualTo("HTTPS://alice:pass-one@api.example.test/login?token=private-value");
		assertThat(report.snapshot().markdown()).contains("alice", "pass-one", "private-value");
		assertThat(defaultGsonBuilder().create().toJson(report)).contains("alice", "pass-one", "private-value");
	}

	@Test
	void longQuotedLiteralsKeepOriginalPrefixWithExplicitSnippetTruncation() {
		String original = "String x = \"https://api.example.test/v1?token=secret" + " smallvalue".repeat(20_000) + "\";";
		AnalysisReport report = new AnalysisReport();
		new AnalysisRules(report).scan(original, "Long.java", null);
		assertThat(report.markdown()).contains("smallvalue", "token=secret");
		assertThat(report.getFindings().get(0).getEvidence().get(0).getCode()).hasSizeLessThanOrEqualTo(1800);
		assertThat(report.getFindings().get(0).getEvidence().get(0).isTruncated()).isTrue();
	}

	@Test
	void packagedResourceReferencesLeadToMethodsWithoutClaimingDataFlow() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.scan("-----BEGIN PRIVATE KEY-----", "assets/client.pem", null);
		rules.indexResourceReferences();
		rules.linkResource("unrelated.pem", "Reader", JadxNodeRef.forCls("Reader"));
		assertThat(report.getFindings().get(0).getEvidence()).hasSize(1);
		rules.linkResource("client.pem", "Reader", JadxNodeRef.forCls("Reader"));
		assertThat(report.getFindings().get(0).getEvidence()).hasSize(2);
		assertThat(report.getFindings().get(0).getEvidence().get(1).getNode()).isEqualTo(JadxNodeRef.forCls("Reader"));
		assertThat(report.getFindings().get(0).getEvidence().get(1).getCode()).contains("does not prove");
	}

	@Test
	void distinguishesCredentialsFromKeywordsAndKeepsOriginalEvidence() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.scan("String password = input;\nString passwordLabel = \"password\";", "NoCredentials", null);
		assertThat(report.getFindings()).isEmpty();
		rules.scan("String password = \"demo-secret-9842\";\nString url = \"https://api.example.test/login?token=private-value\";",
				"Login.java", JadxNodeRef.forCls("Login"));
		assertThat(report.getFindings()).extracting(AnalysisFinding::getKind).containsExactly(Kind.CREDENTIAL, Kind.ENDPOINT);
		assertThat(report.markdown()).contains("demo-secret-9842", "private-value", "Login.java:1");
		assertThat(report.getFindings().get(0).getRawValue()).isEqualTo("demo-secret-9842");
	}

	@Test
	void scansResourceSecretsWithoutTreatingPublicIdentifiersAsCredentials() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.scan("<resources><string name=\"service_password\">resource-secret</string>"
				+ "<string name=\"google_api_key\">public-project-key</string></resources>", "res/values/strings.xml", null);
		rules.scan("client_secret: plain-secret", "assets/service.yml", null);
		rules.scan("-----BEGIN PRIVATE KEY-----\nKEY_MATERIAL_HERE\n-----END PRIVATE KEY-----", "assets/key.pem", null);
		assertThat(report.getFindings()).extracting(AnalysisFinding::getKind)
				.containsExactly(Kind.CREDENTIAL, Kind.PUBLIC_CONFIG, Kind.CREDENTIAL, Kind.PRIVATE_KEY);
		assertThat(report.markdown()).contains("resource-secret", "plain-secret", "KEY_MATERIAL_HERE", "public-project-key");
		assertThat(report.getFindings().get(3).getRawValue()).contains("KEY_MATERIAL_HERE");
	}

	@Test
	void sameValueDeduplicatesAndPublishedSnapshotsDoNotChange() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.scan("https://api.example.test/v1", "One", null);
		AnalysisReport snapshot = report.snapshot();
		rules.scan("https://api.example.test/v1", "Two", null);
		assertThat(report.getFindings()).hasSize(1);
		assertThat(report.getFindings().get(0).getOccurrences()).isEqualTo(2);
		assertThat(snapshot.getFindings().get(0).getOccurrences()).isEqualTo(1);
		assertThat(snapshot.getFindings().get(0).getEvidence()).hasSize(1);
	}

	@Test
	void savedProjectRestoresOriginalValuesAndSourceEvidence() {
		AnalysisReport report = new AnalysisReport();
		new AnalysisRules(report).scan("password=\"preserve-this-value\"", "Config", JadxNodeRef.forCls("Config"));
		report.setFingerprint("fingerprint");
		report.setComplete(true);
		ProjectData project = new ProjectData();
		project.setAnalysisReport(report);
		var gson = defaultGsonBuilder().create();
		String json = gson.toJson(project);
		assertThat(json).contains("preserve-this-value");
		AnalysisReport restored = gson.fromJson(json, ProjectData.class).getAnalysisReport();
		assertThat(restored.getFindings().get(0).getRawValue()).isEqualTo("preserve-this-value");
		assertThat(restored.getFindings().get(0).getValue()).isEqualTo("preserve-this-value");
		assertThat(restored.getFindings().get(0).getEvidence().get(0).getCode()).contains("preserve-this-value");
		assertThat(restored.matches("fingerprint")).isTrue();
		assertThat(restored.matches("changed")).isFalse();
		assertThat(gson.fromJson("{}", ProjectData.class).getAnalysisReport()).isNull();
		assertThat(gson
				.fromJson(json.replace("\"ruleVersion\": \"" + AnalysisReport.RULE_VERSION + "\"", "\"ruleVersion\": \"old\""),
						ProjectData.class)
				.getAnalysisReport().matches("fingerprint")).isFalse();
	}

	@Test
	void incompleteAndUnfingerprintedReportsCannotBeReused() {
		AnalysisReport report = new AnalysisReport();
		report.setFingerprint("same-file");
		assertThat(report.matches("same-file")).isFalse();
		report.setComplete(true);
		assertThat(report.matches("same-file")).isTrue();
		assertThat(report.matches(null)).isFalse();
		report.setFingerprint(""); // Directory inputs cannot be verified by the file fingerprint.
		assertThat(report.matches("")).isFalse();
		report.setFingerprint(null);
		assertThat(report.matches(null)).isFalse();
	}

	@Test
	void modelSelectionCannotIntroduceUnknownEvidenceOrExceedBudget() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		for (int i = 0; i < 12; i++) {
			rules.scan("https://api.example.test/" + i, "Source" + i, null);
		}
		assertThat(AnalysisInvestigator.select("{\"inspect\":[\"F999\",\"F2\",\"F2\",\"F1\"]}", report.getFindings()))
				.extracting(AnalysisFinding::getId).containsExactly("F2", "F1");
		assertThat(AnalysisInvestigator.select("\"F1\"\"F2\"\"F3\"\"F4\"\"F5\"\"F6\"\"F7\"", report.getFindings())).hasSize(6);
		assertThat(AnalysisInvestigator.select("x".repeat(5000), List.of())).isEmpty();
	}

	@Test
	void endpointFloodDoesNotHidePrivateKeyFindings() {
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		for (int i = 0; i < 250; i++) {
			rules.scan("https://api.example.test/" + i, "Source", null);
		}
		rules.scan("-----BEGIN PRIVATE KEY-----", "assets/key.pem", null);
		assertThat(report.getFindings()).hasSize(151);
		assertThat(report.getFindings()).extracting(AnalysisFinding::getKind).contains(Kind.PRIVATE_KEY);
		assertThat(report.getGaps()).isNotEmpty();
	}

	@Test
	void configurationFlagsRemainContextualObservations() {
		AnalysisReport report = new AnalysisReport();
		new AnalysisRules(report).scan("<application android:allowBackup=\"true\" android:debuggable=\"true\">"
				+ "<activity android:name=\".Login\" android:exported=\"true\" /></application>", "AndroidManifest.xml", null);
		assertThat(report.getFindings()).extracting(AnalysisFinding::getKind).containsExactly(Kind.EXPORTED, Kind.DEBUGGABLE, Kind.BACKUP);
	}
}
