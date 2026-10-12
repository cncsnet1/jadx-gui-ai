package jadx.gui.ui.panel;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
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

	public StartPagePanel(TabbedPane tabbedPane, StartPageNode node) {
		super(tabbedPane, node);
		buildUi();
	}

	private void buildUi() {
		removeAll();
		setLayout(new BorderLayout());
		MainWindow window = getMainWindow();
		Landing page = new Landing();
		page.setLayout(new BorderLayout(0, 26));
		page.setBorder(BorderFactory.createEmptyBorder(32, 36, 26, 36));
		page.setBackground(WorkbenchUi.canvas());
		JPanel hero = WorkbenchUi.transparent();
		hero.setLayout(new BorderLayout(24, 0));
		Blueprint graphic = new Blueprint();
		graphic.setPreferredSize(new Dimension(330, 246));
		page.graphic = graphic;
		hero.add(graphic, BorderLayout.EAST);
		JPanel introduction = WorkbenchUi.transparent();
		introduction.setLayout(new BoxLayout(introduction, BoxLayout.PAGE_AXIS));
		JLabel eyebrow = WorkbenchUi.mutedLabel("REVERSE ENGINEERING  /  REIMAGINED");
		eyebrow.setForeground(WorkbenchUi.accent());
		introduction.add(eyebrow);
		introduction.add(Box.createVerticalStrut(20));
		introduction.add(WorkbenchUi.title(AiText.str("studio_hero_line1"), 26));
		JLabel secondLine = WorkbenchUi.title(AiText.str("studio_hero_line2"), 26);
		secondLine.setForeground(WorkbenchUi.accent());
		introduction.add(secondLine);
		introduction.add(Box.createVerticalStrut(14));
		JTextArea subtitle = WorkbenchUi.body(AiText.str("studio_hero_body"));
		subtitle.setForeground(WorkbenchUi.muted());
		subtitle.setMaximumSize(new Dimension(540, 44));
		introduction.add(subtitle);
		introduction.add(Box.createVerticalStrut(20));
		JPanel actions = WorkbenchUi.transparent();
		actions.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 0, 0));
		actions.getAccessibleContext().setAccessibleName(NLS.str("start_page.start"));
		JButton open = new JButton(AiText.str("studio_open_file"), new WorkspaceIcon(WorkspaceIcon.Kind.ARROW, 18));
		open.setPreferredSize(new Dimension(180, 42));
		open.setHorizontalTextPosition(SwingConstants.LEADING);
		open.setIconTextGap(20);
		WorkbenchUi.primary(open);
		open.addActionListener(e -> window.openFileDialog());
		actions.add(open);
		actions.add(Box.createHorizontalStrut(12));
		JButton project = new JButton(NLS.str("file.open_project"));
		project.setPreferredSize(new Dimension(160, 42));
		project.addActionListener(e -> window.openProjectDialog());
		actions.add(project);
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
		introduction.add(actions);
		for (java.awt.Component component : introduction.getComponents()) {
			if (component instanceof javax.swing.JComponent) {
				((javax.swing.JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
			}
		}
		hero.add(introduction);
		page.add(hero, BorderLayout.NORTH);

		JPanel lower = WorkbenchUi.transparent();
		lower.setLayout(new GridLayout(1, 2, 20, 0));
		JPanel recent = WorkbenchUi.card(22);
		JPanel recentHeading = WorkbenchUi.transparent();
		recentHeading.add(WorkbenchUi.title(NLS.str("start_page.recent"), 5), BorderLayout.WEST);
		recentHeading.add(WorkbenchUi.mutedLabel("WORKSPACE"), BorderLayout.EAST);
		recent.add(recentHeading, BorderLayout.NORTH);
		recentPanel.setOpaque(false);
		JScrollPane recentScroll = new JScrollPane(recentPanel);
		recentScroll.setOpaque(false);
		recentScroll.getViewport().setOpaque(false);
		recentScroll.setBorder(BorderFactory.createEmptyBorder());
		recentScroll.setPreferredSize(new Dimension(300, 185));
		recent.add(recentScroll);
		fillRecentPanel();
		lower.add(recent);
		JPanel guide = WorkbenchUi.card(22);
		guide.add(WorkbenchUi.title(AiText.str("studio_flow_title"), 5), BorderLayout.NORTH);
		JPanel steps = WorkbenchUi.transparent();
		steps.setLayout(new GridLayout(3, 1, 0, 10));
		steps.add(step("01", "studio_flow_open", "studio_flow_open_hint"));
		steps.add(step("02", "studio_flow_find", "studio_flow_find_hint"));
		steps.add(step("03", "studio_flow_analyze", "studio_flow_analyze_hint"));
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

	private JPanel step(String number, String title, String hint) {
		JPanel row = WorkbenchUi.transparent();
		JLabel index = WorkbenchUi.title(number, 1);
		index.setForeground(WorkbenchUi.accent());
		index.setPreferredSize(new Dimension(36, 36));
		row.add(index, BorderLayout.WEST);
		JPanel text = WorkbenchUi.transparent();
		text.add(WorkbenchUi.title(AiText.str(title), 0), BorderLayout.NORTH);
		text.add(WorkbenchUi.mutedLabel(AiText.str(hint)), BorderLayout.CENTER);
		row.add(text);
		return row;
	}

	private void fillRecentPanel() {
		recentPanel.removeAll();
		recentPanel.setLayout(new BoxLayout(recentPanel, BoxLayout.PAGE_AXIS));
		MainWindow window = getMainWindow();
		if (window.getSettings().getRecentProjects().isEmpty()) {
			recentPanel.add(Box.createVerticalStrut(22));
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
		private Blueprint graphic;

		@Override
		public void doLayout() {
			graphic.setVisible(getWidth() >= 940);
			super.doLayout();
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return new Dimension(1040, 630);
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

	/** A conceptual source map; intentionally contains no invented project metrics. */
	private static final class Blueprint extends JPanel {
		Blueprint() {
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(WorkbenchUi.line());
			for (int x = 8; x < getWidth(); x += 20) {
				for (int y = 4; y < getHeight(); y += 20) {
					g.fillOval(x, y, 2, 2);
				}
			}
			g.setStroke(new BasicStroke(1.4f));
			Path2D path = new Path2D.Float();
			path.moveTo(55, 60);
			path.curveTo(150, 60, 95, 132, 165, 132);
			path.curveTo(240, 132, 210, 65, 285, 65);
			path.moveTo(165, 132);
			path.curveTo(165, 212, 250, 212, 280, 212);
			g.setColor(WorkbenchUi.accent());
			g.draw(path);
			node(g, 8, 35, 108, 46, "APK / JAR", false);
			node(g, 117, 100, 116, 65, "</>", true);
			node(g, 233, 37, 89, 46, "SOURCE", false);
			node(g, 232, 187, 90, 46, "AI", false);
			g.setColor(WorkbenchUi.muted());
			g.setFont(getFont().deriveFont(10f));
			g.drawString("READ  →  CONNECT  →  UNDERSTAND", 8, getHeight() - 4);
			g.dispose();
		}

		private void node(Graphics2D g, int x, int y, int width, int height, String text, boolean main) {
			g.setColor(main ? WorkbenchUi.tint() : WorkbenchUi.surface());
			g.fillRoundRect(x, y, width, height, 16, 16);
			g.setColor(main ? WorkbenchUi.accent() : WorkbenchUi.line());
			g.drawRoundRect(x, y, width, height, 16, 16);
			g.setColor(main ? WorkbenchUi.accent() : getForeground());
			g.setFont(getFont().deriveFont(Font.BOLD, main ? 25f : 12f));
			g.drawString(text, x + (width - g.getFontMetrics().stringWidth(text)) / 2,
					y + (height + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2);
		}
	}
}
