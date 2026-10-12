package jadx.gui.ai.analysis;

import jadx.gui.ai.AiText;

public enum AnalysisGoal {
	EXPLORE, SECURITY, OVERVIEW, SECRETS, NETWORK, LOGIN, BEHAVIOR;

	public String key() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	@Override
	public String toString() {
		return AiText.str("analysis.goal." + key());
	}
}
