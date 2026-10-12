package jadx.gui.ui.panel;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

import jadx.gui.ai.AiText;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.dialog.AiSettingDialog;
import jadx.gui.ui.tab.TabbedPane;
import jadx.gui.ui.treenodes.StartPageNode;
import jadx.gui.ui.workspace.WorkspaceIcon;
import jadx.gui.utils.Icons;
import jadx.gui.utils.NLS;
import jadx.gui.utils.ui.WorkbenchUi;

public class StartPagePanel extends ContentPanel {
	private final JPanel recentPanel = new JPanel();
	private JLabel dropTitle;
	private JTextArea dropHint;
	private DropZone dropZone;
	private boolean fileDropActive;

	public StartPagePanel(TabbedPane tabbedPane, StartPageNode node) {
		super(tabbedPane, node);
		buildUi();
	}

	private void buildUi() {
		removeAll();
		setLayout(new BorderLayout());
		MainWindow window = getMainWindow();
		Landing page = new Landing();
		page.setLayout(new BorderLayout(0, 20));
		page.setBorder(BorderFactory.createEmptyBorder(24, 28, 20, 28));
		page.setBackground(WorkbenchUi.canvas());
		JPanel top = WorkbenchUi.transparent();
		top.setLayout(new BorderLayout(0, 18));
		JPanel heading = WorkbenchUi.transparent();
		boolean loaded = window.isProjectLoaded();
		heading.add(WorkbenchUi.title(AiText.str(loaded ? "studio_resume_title" : "studio_hero_line1"), 14), BorderLayout.NORTH);
		heading.add(WorkbenchUi.body(AiText.str(loaded ? "studio_resume_hint" : "studio_hero_body")), BorderLayout.CENTER);
		if (loaded) {
			JPanel current = WorkbenchUi.card(16);
			JLabel file = WorkbenchUi.title(window.getProject().getFilePaths().get(0).getFileName().toString(), 2);
			file.setToolTipText(window.getProject().getFilePaths().get(0).toString());
			current.add(file, BorderLayout.CENTER);
			JButton resume = new JButton(AiText.str("studio_resume"), new WorkspaceIcon(WorkspaceIcon.Kind.ARROW, 18));
			WorkbenchUi.primary(resume);
			resume.addActionListener(e -> window.showWorkspaceExplorer());
			current.add(resume, BorderLayout.EAST);
			heading.add(current, BorderLayout.SOUTH);
		}
		top.add(heading, BorderLayout.NORTH);
		fileDropActive = false;
		dropZone = new DropZone();
		dropZone.setLayout(new BorderLayout(24, 0));
		dropZone.setBorder(BorderFactory.createEmptyBorder(28, 28, 26, 28));
		dropZone.setPreferredSize(new Dimension(620, 232));
		dropZone.getAccessibleContext().setAccessibleName(AiText.str("studio_drop_title"));
		JLabel icon = new JLabel(new WorkspaceIcon(WorkspaceIcon.Kind.FILES, 44));
		icon.setForeground(WorkbenchUi.accent());
		icon.setVerticalAlignment(SwingConstants.TOP);
		icon.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
		dropZone.add(icon, BorderLayout.WEST);
		JPanel introduction = WorkbenchUi.transparent();
		introduction.setLayout(new BoxLayout(introduction, BoxLayout.PAGE_AXIS));
		dropTitle = WorkbenchUi.accentTitle(AiText.str("studio_drop_title"), 16);
		introduction.add(dropTitle);
		introduction.add(Box.createVerticalStrut(10));
		dropHint = WorkbenchUi.body(AiText.str("studio_drop_body"));
		dropHint.setForeground(WorkbenchUi.muted());
		dropHint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
		introduction.add(dropHint);
		introduction.add(Box.createVerticalStrut(14));
		introduction.add(WorkbenchUi.mutedLabel("APK   ·   DEX   ·   JAR   ·   CLASS   ·   ZIP"));
		introduction.add(Box.createVerticalStrut(20));
		JPanel actions = WorkbenchUi.transparent();
		actions.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 0, 0));
		actions.getAccessibleContext().setAccessibleName(NLS.str("start_page.start"));
		JButton open = new JButton(AiText.str("studio_open_file"), new WorkspaceIcon(WorkspaceIcon.Kind.ARROW, 18));
		open.setPreferredSize(new Dimension(150, 40));
		open.setHorizontalTextPosition(SwingConstants.LEADING);
		open.setIconTextGap(20);
		if (!loaded) {
			WorkbenchUi.primary(open);
		}
		open.addActionListener(e -> window.openFileDialog());
		actions.add(open);
		actions.add(Box.createHorizontalStrut(12));
		JButton project = new JButton(NLS.str("file.open_project"));
		project.setPreferredSize(new Dimension(160, 40));
		project.addActionListener(e -> window.openProjectDialog());
		actions.add(project);
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		introduction.add(actions);
		for (java.awt.Component component : introduction.getComponents()) {
			if (component instanceof javax.swing.JComponent) {
				((javax.swing.JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
			}
		}
		dropZone.add(introduction);
		top.add(dropZone, BorderLayout.CENTER);
		page.add(top, BorderLayout.NORTH);

		JPanel lower = WorkbenchUi.transparent();
		lower.setLayout(new GridLayout(1, 2, 20, 0));
		JPanel recent = WorkbenchUi.card(22);
		JPanel recentHeading = WorkbenchUi.transparent();
		recentHeading.add(WorkbenchUi.title(NLS.str("start_page.recent"), 5), BorderLayout.WEST);
		recent.add(recentHeading, BorderLayout.NORTH);
		recentPanel.setOpaque(false);
		JScrollPane recentScroll = new JScrollPane(recentPanel);
		recentScroll.setOpaque(false);
		recentScroll.getViewport().setOpaque(false);
		recentScroll.setBorder(BorderFactory.createEmptyBorder());
		recentScroll.setPreferredSize(new Dimension(300, 150));
		recent.add(recentScroll);
		fillRecentPanel();
		lower.add(recent);
		JPanel guide = WorkbenchUi.card(22);
		guide.add(WorkbenchUi.title(AiText.str("studio_flow_title"), 5), BorderLayout.NORTH);
		JPanel steps = WorkbenchUi.transparent();
		steps.setLayout(new GridLayout(2, 1, 0, 10));
		steps.add(step("01", "studio_flow_open", "studio_flow_open_hint"));
		steps.add(step("02", "studio_flow_analyze", "studio_flow_analyze_hint"));
		guide.add(steps);
		JButton configure = new JButton(AiText.str("studio_configure"), new WorkspaceIcon(WorkspaceIcon.Kind.SETTINGS, 16));
		configure.putClientProperty("JButton.buttonType", "toolBarButton");
		configure.setHorizontalAlignment(SwingConstants.LEADING);
		configure.addActionListener(e -> new AiSettingDialog(window, window.getSettings()).setVisible(true));
		guide.add(configure, BorderLayout.SOUTH);
		lower.add(guide);
		page.add(lower);
		JPanel footnote = WorkbenchUi.transparent();
		footnote.add(WorkbenchUi.mutedLabel(AiText.str("studio_drop_hint")), BorderLayout.WEST);
		footnote.add(WorkbenchUi.mutedLabel("Ctrl / ⌘ + K  ·  " + AiText.str("studio_commands")), BorderLayout.EAST);
		page.add(footnote, BorderLayout.SOUTH);
		JScrollPane scroll = new JScrollPane(page);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(24);
		add(scroll);
	}

	public void setFileDropActive(boolean active) {
		if (fileDropActive == active) {
			return;
		}
		fileDropActive = active;
		dropTitle.setText(AiText.str(active ? "studio_drop_release" : "studio_drop_title"));
		dropHint.setText(AiText.str(active ? "studio_drop_release_hint" : "studio_drop_body"));
		dropZone.repaint();
	}

	private JPanel step(String number, String title, String hint) {
		JPanel row = WorkbenchUi.transparent();
		JLabel index = WorkbenchUi.title(number, 1);
		index.setForeground(WorkbenchUi.accent());
		index.setPreferredSize(new Dimension(36, 36));
		row.add(index, BorderLayout.WEST);
		JPanel text = WorkbenchUi.transparent();
		text.add(WorkbenchUi.title(AiText.str(title), 0), BorderLayout.NORTH);
		JTextArea description = WorkbenchUi.body(AiText.str(hint));
		description.setForeground(WorkbenchUi.muted());
		text.add(description, BorderLayout.CENTER);
		row.add(text);
		return row;
	}

	private void fillRecentPanel() {
		recentPanel.removeAll();
		recentPanel.setLayout(new BoxLayout(recentPanel, BoxLayout.PAGE_AXIS));
		MainWindow window = getMainWindow();
		if (window.getSettings().getRecentProjects().isEmpty()) {
			recentPanel.add(Box.createVerticalStrut(8));
			JLabel icon = new JLabel(new WorkspaceIcon(WorkspaceIcon.Kind.FILES, 30));
			icon.setForeground(WorkbenchUi.muted());
			recentPanel.add(icon);
			recentPanel.add(Box.createVerticalStrut(16));
			recentPanel.add(WorkbenchUi.title(AiText.str("studio_recent_empty"), 1));
			recentPanel.add(Box.createVerticalStrut(10));
			recentPanel.add(WorkbenchUi.body(AiText.str("home_recent_empty")));
			for (java.awt.Component component : recentPanel.getComponents()) {
				if (component instanceof javax.swing.JComponent) {
					((javax.swing.JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
				}
			}
		}
		for (Path path : window.getSettings().getRecentProjects()) {
			JButton open = new JButton(path.getFileName().toString(), new WorkspaceIcon(WorkspaceIcon.Kind.FILES, 18));
			open.putClientProperty("html.disable", true);
			open.putClientProperty("JButton.buttonType", "toolBarButton");
			open.setHorizontalAlignment(JButton.LEADING);
			open.setToolTipText(path.toAbsolutePath().toString());
			open.addActionListener(e -> window.open(path));
			JPanel text = WorkbenchUi.transparent();
			text.add(open, BorderLayout.NORTH);
			JLabel location = WorkbenchUi.mutedLabel(path.toAbsolutePath().toString());
			location.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
			text.add(location, BorderLayout.SOUTH);
			JButton remove = new JButton(Icons.CLOSE_INACTIVE);
			remove.putClientProperty("JButton.buttonType", "toolBarButton");
			remove.setToolTipText(AiText.str("home_remove_recent"));
			remove.getAccessibleContext().setAccessibleName(AiText.str("home_remove_recent"));
			remove.addActionListener(e -> {
				window.getSettings().removeRecentProject(path);
				fillRecentPanel();
				recentPanel.revalidate();
				recentPanel.repaint();
			});
			JPanel row = WorkbenchUi.transparent();
			row.setBorder(BorderFactory.createEmptyBorder(4, 0, 10, 0));
			row.add(text, BorderLayout.CENTER);
			row.add(remove, BorderLayout.EAST);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			recentPanel.add(row);
		}
		recentPanel.add(Box.createVerticalGlue());
	}

	@Override
	public void loadSettings() {
		buildUi();
		revalidate();
		repaint();
	}

	private static final class Landing extends JPanel implements Scrollable {

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return new Dimension(1040, 600);
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
			return 24;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
			return visible.height - 24;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
		}
	}

	private final class DropZone extends JPanel {
		DropZone() {
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(fileDropActive ? WorkbenchUi.tint() : WorkbenchUi.surface());
			g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
			g.setColor(fileDropActive ? WorkbenchUi.accent() : WorkbenchUi.line());
			g.setStroke(fileDropActive ? new BasicStroke(2f)
					: new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] { 7, 6 }, 0));
			g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
			g.dispose();
		}
	}
}
