package jadx.gui.ui.workspace;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import jadx.gui.ai.AiText;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.menu.HiddenMenuItem;
import jadx.gui.utils.ui.WorkbenchUi;

/** Commands reuse the live menu items, including plugin actions, toggles and enabled state. */
public final class CommandPaletteDialog extends JDialog {
	private final JTextField query = new JTextField();
	private final DefaultListModel<Command> model = new DefaultListModel<>();
	private final JList<Command> results = new JList<>(model);
	private final JLabel status = WorkbenchUi.mutedLabel("");
	private final List<Command> commands;

	public CommandPaletteDialog(MainWindow window) {
		super(window, AiText.str("studio_commands"), false);
		commands = collect(window.getJMenuBar());
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		JPanel content = WorkbenchUi.card(20);
		JPanel heading = WorkbenchUi.transparent();
		heading.add(WorkbenchUi.title(AiText.str("studio_commands"), 9), BorderLayout.NORTH);
		query.putClientProperty("JTextField.placeholderText", AiText.str("studio_command_hint"));
		query.putClientProperty("JTextField.leadingIcon", new WorkspaceIcon(WorkspaceIcon.Kind.SEARCH, 20));
		query.setPreferredSize(new Dimension(400, 44));
		query.getAccessibleContext().setAccessibleName(AiText.str("studio_commands"));
		heading.add(query, BorderLayout.SOUTH);
		content.add(heading, BorderLayout.NORTH);
		results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		results.setFixedCellHeight(48);
		DefaultListCellRenderer renderer = new DefaultListCellRenderer();
		results.setCellRenderer((list, command, index, selected, focus) -> {
			JLabel label = (JLabel) renderer.getListCellRendererComponent(list, command, index, selected, focus);
			label.putClientProperty("html.disable", true);
			label.setText(command.label());
			label.setEnabled(command.isEnabled());
			label.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
			return label;
		});
		JScrollPane scroll = new JScrollPane(results);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		content.add(scroll, BorderLayout.CENTER);
		content.add(status, BorderLayout.SOUTH);
		setContentPane(content);
		query.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				filter();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				filter();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				filter();
			}
		});
		bind(KeyEvent.VK_DOWN, () -> move(1));
		bind(KeyEvent.VK_UP, () -> move(-1));
		query.addActionListener(e -> execute());
		results.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "execute");
		results.getActionMap().put("execute", action(this::execute));
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
		results.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int index = results.locationToIndex(e.getPoint());
				if (e.getClickCount() == 2 && index >= 0 && results.getCellBounds(index, index).contains(e.getPoint())) {
					execute();
				}
			}
		});
		filter();
		setSize(Math.min(760, window.getWidth() - 80), Math.min(510, window.getHeight() - 100));
		setLocation(window.getX() + (window.getWidth() - getWidth()) / 2, window.getY() + 100);
		SwingUtilities.invokeLater(query::requestFocusInWindow);
	}

	private void filter() {
		model.clear();
		for (Command command : commands) {
			if (command.matches(query.getText())) {
				model.addElement(command);
			}
		}
		if (!model.isEmpty()) {
			results.setSelectedIndex(0);
		}
		status.setText(model.isEmpty() ? AiText.str("studio_no_commands") : AiText.str("studio_command_keys"));
	}

	private void move(int delta) {
		if (!model.isEmpty()) {
			int index = Math.floorMod(results.getSelectedIndex() + delta, model.size());
			results.setSelectedIndex(index);
			results.ensureIndexIsVisible(index);
		}
	}

	private void execute() {
		Command command = results.getSelectedValue();
		if (command != null && command.isEnabled()) {
			dispose();
			command.item.doClick();
		}
	}

	private void bind(int key, Runnable runnable) {
		String name = "command-" + key;
		query.getInputMap().put(KeyStroke.getKeyStroke(key, 0), name);
		query.getActionMap().put(name, action(runnable));
	}

	private static AbstractAction action(Runnable runnable) {
		return new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				runnable.run();
			}
		};
	}

	static List<Command> collect(JMenuBar bar) {
		List<Command> found = new ArrayList<>();
		for (int i = 0; i < bar.getMenuCount(); i++) {
			JMenu menu = bar.getMenu(i);
			if (menu != null) {
				collect(menu, "", List.of(), found);
			}
		}
		return found;
	}

	private static void collect(JMenu menu, String path, List<JMenu> parents, List<Command> found) {
		List<JMenu> ancestry = new ArrayList<>(parents);
		ancestry.add(menu);
		String category = path.isEmpty() ? menu.getText() : path + " / " + menu.getText();
		for (Component component : menu.getMenuComponents()) {
			if (component instanceof JMenu) {
				collect((JMenu) component, category, ancestry, found);
			} else if (component instanceof JMenuItem && !(component instanceof HiddenMenuItem) && component.isVisible()) {
				JMenuItem item = (JMenuItem) component;
				if (item.getText() != null && !item.getText().isBlank()) {
					found.add(new Command(item, category, ancestry));
				}
			}
		}
	}

	static final class Command {
		private final JMenuItem item;
		private final String category;
		private final List<JMenu> parents;

		Command(JMenuItem item, String category, List<JMenu> parents) {
			this.item = item;
			this.category = category;
			this.parents = List.copyOf(parents);
		}

		boolean isEnabled() {
			return item.isEnabled() && item.isVisible() && parents.stream().allMatch(menu -> menu.isEnabled() && menu.isVisible());
		}

		boolean matches(String query) {
			String text = (category + " " + item.getText()).toLowerCase(Locale.ROOT);
			for (String token : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
				if (!text.contains(token)) {
					return false;
				}
			}
			return true;
		}

		String label() {
			KeyStroke key = item.getAccelerator();
			String shortcut = key == null
					? ""
					: "    " + InputEvent.getModifiersExText(key.getModifiers())
							+ "+" + KeyEvent.getKeyText(key.getKeyCode());
			return item.getText() + "    /    " + category + shortcut;
		}
	}
}
