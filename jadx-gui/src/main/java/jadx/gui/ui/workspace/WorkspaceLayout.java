package jadx.gui.ui.workspace;

/** Presentation state; home and focus modes never overwrite the user's panel choices. */
public final class WorkspaceLayout {
	private boolean home = true;
	private boolean focus;
	private boolean explorer = true;
	private boolean assistant;

	public WorkspaceLayout(boolean assistant) {
		this.assistant = assistant;
	}

	public void setHome(boolean value) {
		home = value;
	}

	public boolean isHome() {
		return home;
	}

	public boolean isFocus() {
		return focus && !home;
	}

	public void toggleFocus() {
		if (!home) {
			focus = !focus;
		}
	}

	public void toggleExplorer() {
		if (home || focus) {
			home = false;
			focus = false;
			explorer = true;
		} else {
			explorer = !explorer;
		}
	}

	public void setAssistant(boolean value) {
		assistant = value;
		if (value) {
			home = false;
			focus = false;
		}
	}

	public boolean isExplorerVisible() {
		return !home && !focus && explorer;
	}

	public boolean isAssistantVisible() {
		return !home && !focus && assistant;
	}
}
