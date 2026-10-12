package jadx.gui.ui.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceLayoutTest {
	@Test
	void homeDoesNotOverwritePanelChoices() {
		WorkspaceLayout layout = new WorkspaceLayout(true);
		assertThat(layout.isExplorerVisible()).isFalse();
		assertThat(layout.isAssistantVisible()).isFalse();
		layout.setHome(false);
		assertThat(layout.isExplorerVisible()).isTrue();
		assertThat(layout.isAssistantVisible()).isTrue();
		layout.toggleExplorer();
		layout.setHome(true);
		layout.setHome(false);
		assertThat(layout.isExplorerVisible()).isFalse();
		assertThat(layout.isAssistantVisible()).isTrue();
	}

	@Test
	void focusRestoresDifferentPanelPreferences() {
		for (boolean assistant : new boolean[] { false, true }) {
			WorkspaceLayout layout = new WorkspaceLayout(assistant);
			layout.setHome(false);
			layout.toggleExplorer();
			layout.toggleFocus();
			assertThat(layout.isExplorerVisible()).isFalse();
			assertThat(layout.isAssistantVisible()).isFalse();
			layout.setHome(true);
			assertThat(layout.isFocus()).isFalse();
			layout.setHome(false);
			assertThat(layout.isFocus()).isTrue();
			layout.toggleFocus();
			assertThat(layout.isExplorerVisible()).isFalse();
			assertThat(layout.isAssistantVisible()).isEqualTo(assistant);
		}
	}

	@Test
	void navigationCanLeaveHomeAndFocus() {
		WorkspaceLayout layout = new WorkspaceLayout(false);
		layout.toggleFocus();
		assertThat(layout.isFocus()).isFalse();
		layout.toggleExplorer();
		assertThat(layout.isHome()).isFalse();
		assertThat(layout.isExplorerVisible()).isTrue();
		layout.toggleFocus();
		layout.setAssistant(true);
		assertThat(layout.isFocus()).isFalse();
		assertThat(layout.isExplorerVisible()).isTrue();
		assertThat(layout.isAssistantVisible()).isTrue();
	}
}
