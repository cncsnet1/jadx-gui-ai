package jadx.gui.ui.workspace;

/** Presentation state; home and focus modes never overwrite the user's panel choices. */
public final class WorkspaceLayout {
	private boolean home = true;
	private boolean focus;
	private boolean explorer = true;
	private boolean assistant;
	private boolean analysis;

	public WorkspaceLayout(boolean assistant) {
		this.assistant = assistant;
	}

	public void setHome(boolean value) {
		home = value;
		analysis = false;
	}

	public void showAnalysis() {
		home = false;
		analysis = true;
	}

	public boolean isAnalysis() {
		return analysis;
	}

	public boolean isHome() {
		return home;
	}

	public boolean isFocus() {
		return focus && !home && !analysis;
	}

	public void toggleFocus() {
		if (!home && !analysis) {
			focus = !focus;
		}
	}

	public void toggleExplorer() {
		analysis = false;
		if (home || focus) {
			home = false;
			focus = false;
			explorer = true;
		} else {
			explorer = !explorer;
		}
	}

	public void showExplorer() {
		analysis = false;
		home = false;
		focus = false;
		explorer = true;
	}

	public void setAssistant(boolean value) {
		assistant = value;
		if (value) {
			analysis = false;
			home = false;
			focus = false;
		}
	}

	public boolean isExplorerVisible() {
		return !home && !analysis && !focus && explorer;
	}

	public boolean isAssistantVisible() {
		return !home && !analysis && !focus && assistant;
	}
}
