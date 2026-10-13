package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;

import jadx.gui.ai.AiText;
import jadx.gui.utils.ui.WorkbenchUi;

/** A readable local report is the default destination, including without an AI connection. */
public final class ApplicationReportPanel extends JPanel {
	private final JTextArea intro = body("");
	private final JTextArea scope = body("");
	private final JPanel leads = WorkbenchUi.transparent();
	private final JScrollPane scroll = new JScrollPane();
	private final BiConsumer<AnalysisFinding, AnalysisGoal> investigate;
	private final Consumer<AnalysisFinding> inspect;
	private final List<JButton> analysisActions = new ArrayList<>();
	private final List<JButton> leadActions = new ArrayList<>();
	private AnalysisReport report;
	private boolean busy;

	public ApplicationReportPanel(BiConsumer<AnalysisFinding, AnalysisGoal> investigate, Consumer<AnalysisFinding> inspect,
			Runnable showAll, Runnable showApis, Runnable settings) {
		super(new BorderLayout());
		this.investigate = investigate;
		this.inspect = inspect;
		setOpaque(false);
		Document content = new Document();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(18, 6, 18, 6));
		JPanel hero = WorkbenchUi.transparent();
		hero.add(WorkbenchUi.accentTitle(AiText.str("report.local"), 0), BorderLayout.NORTH);
		JPanel heading = WorkbenchUi.transparent();
		heading.add(WorkbenchUi.title(AiText.str("report.heading"), 10), BorderLayout.NORTH);
		heading.add(intro);
		hero.add(heading);
		addSection(content, hero);
		JPanel label = WorkbenchUi.transparent();
		label.add(WorkbenchUi.title(AiText.str("report.leads"), 2));
		JButton all = button("report.all", showAll);
		label.add(all, BorderLayout.EAST);
		addSection(content, label);
		leads.setLayout(new BoxLayout(leads, BoxLayout.Y_AXIS));
		addSection(content, leads);
		JPanel start = WorkbenchUi.card(16);
		start.add(WorkbenchUi.title(AiText.str("report.start"), 2), BorderLayout.NORTH);
		JPanel choices = new JPanel(new GridLayout(0, 1, 6, 7));
		choices.setOpaque(false);
		String[] keys = { "report.goal.api", "report.goal.secret", "report.goal.entry" };
		AnalysisGoal[] goals = { AnalysisGoal.NETWORK, AnalysisGoal.SECRETS, AnalysisGoal.EXPLORE };
		for (int i = 0; i < keys.length; i++) {
			AnalysisGoal goal = goals[i];
			JButton choice = button(keys[i], () -> investigate.accept(null, goal));
			choice.setHorizontalAlignment(JButton.LEADING);
			if (i == 0) {
				WorkbenchUi.primary(choice);
			}
			analysisActions.add(choice);
			choices.add(choice);
		}
		start.add(choices);
		JPanel links = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
		links.setOpaque(false);
		links.add(button("report.apis", showApis));
		links.add(button("report.model", settings));
		start.add(links, BorderLayout.SOUTH);
		addSection(content, start);
		scope.setForeground(WorkbenchUi.muted());
		addSection(content, scope);
		scroll.setViewportView(content);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(18);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		add(scroll);
		setReport(null);
	}

	public void setReport(AnalysisReport value) {
		boolean resetPosition = value == null || report == null || !report.isComplete();
		int position = resetPosition ? 0 : scroll.getVerticalScrollBar().getValue();
		report = value;
		intro.setText(AiText.str(value == null ? "report.waiting" : value.isComplete() ? "report.intro" : "report.scanning"));
		leads.removeAll();
		leadActions.clear();
		if (value != null) {
			Set<String> groups = new LinkedHashSet<>();
			List<AnalysisFinding> selected = new ArrayList<>();
			for (AnalysisFinding finding : AnalysisInvestigator.candidates(value, AnalysisGoal.EXPLORE)) {
				if (groups.add(finding.getKind().group())) {
					selected.add(finding);
				}
				if (selected.size() == 4) {
					break;
				}
			}
			for (AnalysisFinding finding : selected) {
				leads.add(lead(finding));
				leads.add(Box.createVerticalStrut(9));
			}
			if (selected.isEmpty()) {
				leads.add(WorkbenchUi.body(AiText.str(value.isComplete() ? "analysis.no_evidence" : "report.scanning")));
			}
			scope.setText(value.coverage() + "\n" + AiText.str("report.boundary")
					+ (value.getGaps().isEmpty() ? "" : "\n" + String.format(AiText.str("report.gaps"), value.getGaps().size())));
		} else {
			leads.add(WorkbenchUi.body(AiText.str("report.waiting")));
			scope.setText(AiText.str("analysis.local_only"));
		}
		setBusy(busy);
		revalidate();
		repaint();
		javax.swing.SwingUtilities.invokeLater(() -> {
			if (report == value) {
				scroll.getVerticalScrollBar().setValue(position);
			}
		});
	}

	private JPanel lead(AnalysisFinding finding) {
		JPanel card = WorkbenchUi.card(14);
		card.setAlignmentX(LEFT_ALIGNMENT);
		JPanel text = WorkbenchUi.transparent();
		text.add(WorkbenchUi.title(finding.getId() + " · " + finding.getTitle(), 1), BorderLayout.NORTH);
		JTextArea why = body(finding.getKind().text("why") + "\n"
				+ AiText.str("analysis.unknown") + ": " + finding.getKind().text("gap"));
		text.add(why);
		card.add(text);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
		actions.setOpaque(false);
		JButton trace = button("report.trace", () -> investigate.accept(finding,
				finding.getKind().group().equals("network") ? AnalysisGoal.NETWORK : AnalysisGoal.EXPLORE));
		leadActions.add(trace);
		actions.add(trace);
		actions.add(button("report.evidence", () -> inspect.accept(finding)));
		card.add(actions, BorderLayout.SOUTH);
		return card;
	}

	public void setBusy(boolean value) {
		busy = value;
		boolean available = !value && report != null && report.isComplete() && report.hasEvidence();
		analysisActions.forEach(b -> b.setEnabled(available));
		leadActions.forEach(b -> b.setEnabled(available));
	}

	private static void addSection(JPanel parent, JComponent child) {
		child.setAlignmentX(LEFT_ALIGNMENT);
		parent.add(child);
		parent.add(Box.createVerticalStrut(16));
	}

	private static JTextArea body(String text) {
		JTextArea area = new JTextArea(text) {
			@Override
			public void scrollRectToVisible(java.awt.Rectangle rectangle) {
				// Static, non-focusable copy must not scroll the document during layout or theme changes.
			}
		};
		area.setEditable(false);
		area.setFocusable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setFont(javax.swing.UIManager.getFont("Label.font"));
		((javax.swing.text.DefaultCaret) area.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
		return area;
	}

	private static JButton button(String key, Runnable action) {
		JButton button = new JButton(AiText.str(key));
		button.addActionListener(e -> action.run());
		return button;
	}

	static final class Document extends JPanel implements Scrollable {
		Document() {
			setOpaque(false);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return new Dimension(650, 500);
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
			return 18;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
			return 180;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}
}
