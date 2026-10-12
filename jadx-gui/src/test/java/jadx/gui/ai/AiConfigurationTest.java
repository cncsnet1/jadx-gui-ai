package jadx.gui.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.settings.JadxSettings;
import jadx.gui.settings.JadxSettingsAdapter;
import jadx.gui.settings.data.ProjectData;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigurationTest {
	@Test
	void oldSettingsMigrateWithoutOverwritingThemUntilSave() {
		JadxSettings settings = JadxSettingsAdapter.fromString("{\"aiApi\":\"http://localhost:8080/v1\","
				+ "\"aiApiKey\":\"existing-key\",\"aiModel\":\"old-model\",\"AiMaxTokens\":\"8192\"}");
		AiConfiguration draft = AiConfiguration.from(settings);
		AiProfile profile = draft.getDefaultProfile();
		assertThat(profile.getUrl()).isEqualTo("http://localhost:8080/v1");
		assertThat(profile.getApiKey()).isEqualTo("existing-key");
		assertThat(profile.getModel()).isEqualTo("old-model");
		assertThat(profile.getOutputTokens()).isEqualTo(8192);
		profile.setModel("edited-model");
		assertThat(settings.getAiConfiguration()).isNull();
		assertThat(settings.getAiModel()).isEqualTo("old-model");
		settings.setAiConfiguration(draft);
		profile.setModel("unsaved-again");
		assertThat(settings.getAiModel()).isEqualTo("edited-model");
		assertThat(JadxSettingsAdapter.makeString(settings)).contains("\"AiMaxTokens\"").doesNotContain("\"aiMaxTokens\"");
		assertThat(settings.getAiConfiguration().getDefaultProfile().getModel()).isEqualTo("edited-model");
	}

	@Test
	void routesAndProfilesRoundTripUsingTheRealSettingsSerializer() {
		JadxSettings settings = JadxSettings.makeDefault();
		AiConfiguration config = AiConfiguration.from(settings);
		AiProfile main = config.getDefaultProfile();
		main.setName("Local");
		main.setUrl("http://localhost:8080/v1");
		main.setModel("quick");
		AiProfile audit = main.duplicate();
		audit.setName("Audit");
		audit.setModel("deep");
		audit.setUrl("https://example.test/v1");
		config.getProfiles().add(audit);
		config.setTaskProfile("audit", audit.getId());
		config.setTaskProfile("search", audit.getId());
		settings.setAiConfiguration(config);
		JadxSettings restored = JadxSettingsAdapter.fromString(JadxSettingsAdapter.makeString(settings));
		AiConfiguration copy = AiConfiguration.from(restored);
		assertThat(copy.forTask("audit").getModel()).isEqualTo("deep");
		assertThat(copy.forTask("search").getModel()).isEqualTo("deep");
		assertThat(copy.forTask("explain").getModel()).isEqualTo("quick");
		copy.forTask("audit").setModel("draft");
		assertThat(restored.getAiConfiguration().forTask("audit").getModel()).isEqualTo("deep");
		copy.remove(copy.forTask("audit"));
		assertThat(copy.getTaskProfileId("audit")).isNull();
		assertThat(copy.forTask("audit").getModel()).isEqualTo("quick");
	}

	@Test
	void missingRouteAndDefaultIdsFallBackToAnAvailableConnection() {
		AiConfiguration config = AiConfiguration.from(JadxSettings.makeDefault());
		config.setTaskProfile("audit", "deleted");
		config.setDefaultProfile("deleted");
		assertThat(config.forTask("audit")).isSameAs(config.getProfiles().get(0));
	}

	@Test
	void environmentCredentialsAreResolvedLazilyAndNeverSerialized() {
		AiProfile profile = profile();
		profile.setKeyEnvironment("REVERSE_API_KEY");
		assertThat(profile.resolveApiKey(name -> "resolved-secret")).isEqualTo("resolved-secret");
		assertThatThrownBy(() -> profile.resolveApiKey(name -> null)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("REVERSE_API_KEY");
		assertThat(defaultGsonBuilder().create().toJson(profile)).contains("REVERSE_API_KEY").doesNotContain("resolved-secret");
		profile.setKeyEnvironment("bad name");
		assertThatThrownBy(profile::validate).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void previewAndRequestUseTheSameImmutableMessages() {
		AiEvidence source = source("class A {}", true);
		List<Map<String, String>> history = new ArrayList<>(List.of(Map.of("role", "user", "content", "Earlier question"),
				Map.of("role", "assistant", "content", "Earlier answer")));
		AiRequestContext context = new AiRequestContext(List.of(source), history, "Current question");
		history.clear();
		assertThat(context.getMessages()).hasSize(4);
		assertThat(context.getHistoryTurns()).isEqualTo(1);
		assertThat(context.getMessages().get(0).get("content")).contains("[S1] A", "TRUNCATED", "class A {}");
		for (Map<String, String> message : context.getMessages()) {
			assertThat(context.preview()).contains(message.get("content"));
		}
		assertThatThrownBy(() -> context.getMessages().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> context.getMessages().get(1).put("content", "changed")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void modelAccountAndEvidenceChangesStartSeparateConversations() {
		AiProfile profile = profile();
		List<AiEvidence> evidence = List.of(source("class A {}", false));
		String original = AiRequestContext.identity(profile, "audit", evidence);
		assertThat(AiRequestContext.identity(profile.copy(), "audit", evidence)).isEqualTo(original);
		assertThat(AiRequestContext.identity(profile.duplicate(), "audit", evidence)).isNotEqualTo(original);
		profile.setModel("another-model");
		assertThat(AiRequestContext.identity(profile, "audit", evidence)).isNotEqualTo(original);
		profile.setModel("test-model");
		profile.setApiKey("other-account");
		assertThat(AiRequestContext.identity(profile, "audit", evidence)).isNotEqualTo(original);
		assertThat(AiRequestContext.identity(profile, "audit", List.of(source("changed", false)))).isNotEqualTo(original);
	}

	@Test
	void projectReportsRetainModelProvenanceWithoutConnectionSecrets() {
		AiProfile profile = profile();
		profile.setApiKey("private-key");
		ProjectData project = new ProjectData();
		project.getAiRecords().add(new AiRecord("Audit", "Observed [S1]", List.of(source("class A {}", false)), profile));
		String json = defaultGsonBuilder().create().toJson(project);
		assertThat(json).contains("test-model", "example.test").doesNotContain("private-key", "apiKey");
		AiRecord restored = defaultGsonBuilder().create().fromJson(json, ProjectData.class).getAiRecords().get(0);
		assertThat(AiReport.markdown(restored)).contains("test-model", "example.test").doesNotContain("private-key");
	}

	private static AiProfile profile() {
		AiProfile profile = new AiProfile();
		profile.setName("Test");
		profile.setUrl("https://example.test/v1");
		profile.setModel("test-model");
		return profile;
	}

	private static AiEvidence source(String code, boolean truncated) {
		return new AiEvidence(JadxNodeRef.forCls("A"), "A", code, AiEvidence.fingerprint(code), truncated);
	}
}
