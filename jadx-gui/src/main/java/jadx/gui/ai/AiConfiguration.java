package jadx.gui.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jadx.gui.settings.JadxSettings;

/** Editable drafts are independent of the persisted configuration until Save. */
public final class AiConfiguration {
	private List<AiProfile> profiles = new ArrayList<>();
	private String defaultProfileId;
	private Map<String, String> taskProfiles = new HashMap<>();

	public AiConfiguration() {
	}

	public static AiConfiguration from(JadxSettings settings) {
		AiConfiguration saved = settings.getAiConfiguration();
		if (saved != null && !saved.getProfiles().isEmpty()) {
			return saved.copy();
		}
		AiProfile profile = new AiProfile();
		profile.setName(AiText.str("default_profile"));
		profile.setUrl(settings.getAiApi() == null ? "" : settings.getAiApi());
		profile.setApiKey(settings.getAiApiKey() == null ? "" : settings.getAiApiKey());
		profile.setModel(settings.getAiModel() == null ? "" : settings.getAiModel());
		try {
			profile.setOutputTokens(Integer.parseInt(settings.getAiMaxTokens()));
		} catch (RuntimeException e) {
			profile.setOutputTokens(4096);
		}
		AiConfiguration config = new AiConfiguration();
		config.profiles.add(profile);
		config.defaultProfileId = profile.getId();
		return config;
	}

	public AiConfiguration copy() {
		AiConfiguration copy = new AiConfiguration();
		getProfiles().forEach(profile -> copy.profiles.add(profile.copy()));
		copy.defaultProfileId = defaultProfileId;
		copy.taskProfiles.putAll(routes());
		return copy;
	}

	public List<AiProfile> getProfiles() {
		if (profiles == null) {
			profiles = new ArrayList<>();
		}
		return profiles;
	}

	public AiProfile find(String id) {
		return getProfiles().stream().filter(profile -> profile.getId().equals(id)).findFirst().orElse(null);
	}

	public AiProfile getDefaultProfile() {
		AiProfile profile = find(defaultProfileId);
		return profile != null ? profile : getProfiles().isEmpty() ? null : getProfiles().get(0);
	}

	public void setDefaultProfile(String id) {
		defaultProfileId = id;
	}

	public String getTaskProfileId(String task) {
		return routes().get(task);
	}

	public void setTaskProfile(String task, String id) {
		if (id == null) {
			routes().remove(task);
		} else {
			routes().put(task, id);
		}
	}

	public AiProfile forTask(String task) {
		AiProfile profile = find(routes().get(task));
		return profile == null ? getDefaultProfile() : profile;
	}

	public void remove(AiProfile profile) {
		getProfiles().remove(profile);
		routes().values().removeIf(profile.getId()::equals);
		if (profile.getId().equals(defaultProfileId)) {
			defaultProfileId = getProfiles().isEmpty() ? null : getProfiles().get(0).getId();
		}
	}

	private Map<String, String> routes() {
		if (taskProfiles == null) {
			taskProfiles = new HashMap<>();
		}
		return taskProfiles;
	}
}
