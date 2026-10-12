package jadx.gui.ui.workspace;

import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

import org.junit.jupiter.api.Test;

import jadx.gui.ui.menu.HiddenMenuItem;
import jadx.gui.ui.workspace.CommandPaletteDialog.Command;

import static org.assertj.core.api.Assertions.assertThat;

class CommandPaletteTest {
	@Test
	void nestedCommandsKeepLiveAvailabilityAndMenuOwnership() {
		JMenuBar bar = new JMenuBar();
		JMenu tools = new JMenu("Tools");
		JMenu nested = new JMenu("AI");
		JMenuItem item = new JMenuItem("搜索 Search");
		bar.add(tools);
		tools.add(nested);
		nested.add(item);
		List<Command> commands = CommandPaletteDialog.collect(bar);
		assertThat(commands).hasSize(1);
		Command command = commands.get(0);
		assertThat(command.matches("  SEARCH tools ")).isTrue();
		assertThat(command.matches("AI 搜索")).isTrue();
		assertThat(command.matches("search missing")).isFalse();
		assertThat(command.isEnabled()).isTrue();
		tools.setEnabled(false);
		assertThat(command.isEnabled()).isFalse();
		tools.setEnabled(true);
		item.setEnabled(false);
		assertThat(command.isEnabled()).isFalse();
		item.setEnabled(true);
		assertThat(command.isEnabled()).isTrue();
		assertThat(item.getParent()).isSameAs(nested.getPopupMenu());
	}

	@Test
	void excludesInvisibleAndShortcutOnlyItems() {
		JMenuBar bar = new JMenuBar();
		JMenu menu = new JMenu("Navigation");
		bar.add(menu);
		JMenuItem hidden = new JMenuItem("Hidden");
		hidden.setVisible(false);
		menu.add(hidden);
		menu.addSeparator();
		menu.add(new HiddenMenuItem(new AbstractAction("Shortcut variant") {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
			}
		}));
		menu.add(new JMenuItem("Back"));
		assertThat(CommandPaletteDialog.collect(bar)).hasSize(1);
	}
}
