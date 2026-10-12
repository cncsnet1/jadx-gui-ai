package jadx.gui.ui.workspace;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

import jadx.gui.ai.AiText;
import jadx.gui.mcp.McpText;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.panel.ContentPanel;
import jadx.gui.ui.treenodes.StartPageNode;
import jadx.gui.utils.UiUtils;
import jadx.gui.utils.ui.WorkbenchUi;

import static jadx.gui.ui.workspace.WorkspaceIcon.Kind.*;

/** Window chrome owns navigation, command entry and status; code/docking stay in MainWindow. */
public final class WorkspaceChrome extends JPanel {
	private final MainWindow window;
	private final JButton mcpStatus = new JButton();
	private final JLabel project = WorkbenchUi.title("", 1);
	private final JLabel location = WorkbenchUi.mutedLabel("");
	private final JLabel mode = WorkbenchUi.mutedLabel("");
	private final JButton home;
	private final JButton explorer;
	private final JButton analysis;
	private final JButton assistant;
	private final JButton focus;
	private final CardLayout contentLayout = new CardLayout();
	private final JPanel content = new JPanel(contentLayout);
	private final ProjectLoadPanel loadPanel;

	public WorkspaceChrome(MainWindow window, JComponent workspace, JComponent heap, JComponent update,
			Action openAction, Action searchAction, Action settingsAction, Action logAction) {
		super(new BorderLayout());
		this.window = window;
		setOpaque(false);
		JPanel header = WorkbenchUi.transparent();
		header.setBorder(BorderFactory.createEmptyBorder(12, 20, 14, 20));
		JPanel identity = WorkbenchUi.transparent();
		JLabel brand = WorkbenchUi.accentTitle("JADX  /  AI", 7);
		brand.setIcon(new WorkspaceIcon(LOGO, 30));
		brand.setIconTextGap(12);
		identity.add(brand, BorderLayout.NORTH);
		identity.add(project, BorderLayout.SOUTH);
		identity.setPreferredSize(new Dimension(230, 54));
		header.add(identity, BorderLayout.WEST);
		JButton commands = new JButton(AiText.str("studio_command_hint"), new WorkspaceIcon(SEARCH, 18));
		commands.setHorizontalAlignment(SwingConstants.LEADING);
		commands.setIconTextGap(12);
		commands.setToolTipText(AiText.str("studio_commands") + " · Ctrl / ⌘ + K");
		commands.setPreferredSize(new Dimension(260, 38));
		commands.addActionListener(e -> showCommands());
		JPanel commandArea = WorkbenchUi.transparent();
		commandArea.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 20));
		commandArea.add(commands);
		header.add(commandArea, BorderLayout.CENTER);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 8));
		actions.setOpaque(false);
		JButton open = new JButton(openAction);
		open.setText(AiText.str("studio_open"));
		open.setIcon(new WorkspaceIcon(FILES, 17));
		actions.add(open);
		focus = new JButton(AiText.str("studio_focus"), new WorkspaceIcon(FOCUS, 16));
		focus.addActionListener(e -> window.toggleWorkspaceFocus());
		actions.add(focus);
		JButton ai = new JButton(AiText.str("analysis.title"));
		ai.addActionListener(e -> window.showProjectAnalysis());
		ai.setIcon(new WorkspaceIcon(AI, 17));
		WorkbenchUi.primary(ai);
		actions.add(ai);
		header.add(actions, BorderLayout.EAST);
		add(header, BorderLayout.NORTH);

		JPanel rail = WorkbenchUi.transparent();
		rail.setLayout(new BoxLayout(rail, BoxLayout.PAGE_AXIS));
		rail.setBorder(BorderFactory.createEmptyBorder(6, 10, 12, 10));
		home = railButton("studio_home", HOME, window::showWorkspaceHome);
		analysis = railButton("analysis.nav", AI, window::showProjectAnalysis);
		explorer = railButton("studio_files", FILES, window::showWorkspaceExplorer);
		assistant = railButton("studio_assistant", AI,
				() -> window.setAiWorkbenchVisible(!window.getAiWorkbench().isVisible()));
		rail.add(home);
		rail.add(Box.createVerticalStrut(8));
		rail.add(analysis);
		rail.add(Box.createVerticalStrut(8));
		rail.add(explorer);
		rail.add(Box.createVerticalStrut(8));
		JButton search = railButton("studio_search", SEARCH, () -> searchAction.actionPerformed(new ActionEvent(this, 0, "search")));
		search.setEnabled(searchAction.isEnabled());
		searchAction.addPropertyChangeListener(e -> {
			if ("enabled".equals(e.getPropertyName())) {
				search.setEnabled(searchAction.isEnabled());
			}
		});
		rail.add(search);
		rail.add(Box.createVerticalStrut(8));
		rail.add(assistant);
		rail.add(Box.createVerticalGlue());
		rail.add(railButton("studio_logs", CONSOLE, () -> logAction.actionPerformed(new ActionEvent(this, 0, "logs"))));
		rail.add(Box.createVerticalStrut(8));
		rail.add(railButton("studio_settings", SETTINGS, () -> settingsAction.actionPerformed(new ActionEvent(this, 0, "settings"))));
		add(rail, BorderLayout.WEST);

		JPanel stage = WorkbenchUi.transparent();
		stage.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 14));
		loadPanel = new ProjectLoadPanel(window::openFileDialog,
				() -> logAction.actionPerformed(new ActionEvent(this, 0, "logs")));
		content.setOpaque(false);
		content.add(workspace, "workspace");
		content.add(loadPanel, "load");
		stage.add(content, BorderLayout.CENTER);
		add(stage, BorderLayout.CENTER);
		JPanel footer = WorkbenchUi.transparent();
		footer.setBorder(BorderFactory.createEmptyBorder(8, 22, 9, 22));
		footer.add(mode, BorderLayout.WEST);
		footer.add(location, BorderLayout.CENTER);
		JPanel indicators = new JPanel(new FlowLayout(FlowLayout.TRAILING, 12, 0));
		indicators.setOpaque(false);
		mcpStatus.putClientProperty("JButton.buttonType", "toolBarButton");
		mcpStatus.setToolTipText(McpText.str("title"));
		mcpStatus.addActionListener(event -> window.showMcpDialog());
		indicators.add(mcpStatus);
		heap.setPreferredSize(new Dimension(140, 16));
		indicators.add(heap);
		indicators.add(update);
		footer.add(indicators, BorderLayout.EAST);
		add(footer, BorderLayout.SOUTH);
		window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, UiUtils.ctrlButton()), "workspace-commands");
		window.getRootPane().getActionMap().put("workspace-commands", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				showCommands();
			}
		});
		refresh();
	}

	public void showCommands() {
		new CommandPaletteDialog(window).setVisible(true);
	}

	public void showLoading(String fileName) {
		loadPanel.loading(fileName);
		contentLayout.show(content, "load");
	}

	public void showLoadError(String message) {
		loadPanel.failed(message);
		contentLayout.show(content, "load");
	}

	public void showWorkspace() {
		loadPanel.finished();
		contentLayout.show(content, "workspace");
	}

	public void refresh() {
		boolean sharing = window.getMcpManager().server() != null;
		long connections = window.getMcpManager().clients().stream().filter(client -> !client.isClosed()).count();
		mcpStatus.setVisible(sharing || connections > 0);
		mcpStatus.setText(sharing ? McpText.str("sharing_badge") : "MCP · " + connections);
		WorkspaceLayout layout = window.getWorkspaceLayout();
		home.setSelected(layout.isHome());
		analysis.setSelected(layout.isAnalysis());
		analysis.setEnabled(window.isProjectLoaded());
		explorer.setSelected(window.isProjectLoaded() && !layout.isHome() && !layout.isAnalysis());
		explorer.setEnabled(window.isProjectLoaded());
		assistant.setSelected(layout.isAssistantVisible());
		focus.setEnabled(!layout.isHome() && !layout.isAnalysis());
		focus.setText(AiText.str(layout.isFocus() ? "studio_restore" : "studio_focus"));
		project.setText(window.getProject().isInitial() ? AiText.str("studio_workspace") : window.getProject().getName());
		project.setToolTipText(project.getText());
		ContentPanel selected = window.getTabbedPane().getSelectedContentPanel();
		String path = selected == null || selected.getNode() == null || selected.getNode() instanceof StartPageNode
				? AiText.str(window.isProjectLoaded() ? "studio_project_ready_hint" : "studio_ready")
				: selected.getNode().makeLongString();
		if (layout.isAnalysis()) {
			path = AiText.str("analysis.workspace_hint");
		}
		location.setText("    /    " + path);
		location.setToolTipText(path);
		mode.setText("●  " + AiText.str(layout.isFocus() ? "studio_focus_mode" : "studio_local"));
		repaint();
	}

	private JButton railButton(String key, WorkspaceIcon.Kind icon, Runnable action) {
		JButton button = new JButton(AiText.str(key), new WorkspaceIcon(icon, 21)) {
			@Override
			protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				if (isSelected() || getModel().isRollover() || hasFocus()) {
					g.setColor(isSelected() ? WorkbenchUi.tint() : WorkbenchUi.surface());
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
				}
				if (hasFocus()) {
					g.setColor(WorkbenchUi.accent());
					g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
				}
				g.dispose();
				setForeground(isSelected() ? WorkbenchUi.accent() : WorkbenchUi.muted());
				super.paintComponent(graphics);
			}
		};
		button.setAlignmentX(Component.CENTER_ALIGNMENT);
		button.setPreferredSize(new Dimension(76, 64));
		button.setMaximumSize(new Dimension(76, 64));
		button.setHorizontalTextPosition(SwingConstants.CENTER);
		button.setVerticalTextPosition(SwingConstants.BOTTOM);
		button.setIconTextGap(7);
		button.setBorder(BorderFactory.createEmptyBorder(7, 2, 7, 2));
		button.setContentAreaFilled(false);
		button.setFocusPainted(false);
		button.setOpaque(false);
		button.setToolTipText(AiText.str(key));
		button.addActionListener(e -> action.run());
		return button;
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		graphics.setColor(WorkbenchUi.canvas());
		graphics.fillRect(0, 0, getWidth(), getHeight());
	}
}
