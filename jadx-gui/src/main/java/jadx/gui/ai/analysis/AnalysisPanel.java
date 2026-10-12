package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import jadx.api.JadxDecompiler;
import jadx.api.JavaNode;
import jadx.api.ResourceFile;
import jadx.gui.ai.AiConfiguration;
import jadx.gui.ai.AiConnectionError;
import jadx.gui.ai.AiContext;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.AiRecord;
import jadx.gui.ai.AiTaskRunner;
import jadx.gui.ai.AiText;
import jadx.gui.ai.OpenAiClient;
import jadx.gui.settings.JadxProject;
import jadx.gui.treemodel.JResource;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.dialog.AiInvestigationDialog;
import jadx.gui.ui.dialog.AiSettingDialog;
import jadx.gui.utils.ui.WorkbenchUi;

/** Goal-first project workspace. All scanning, context loading and transport run off the EDT. */
public final class AnalysisPanel extends JPanel {
	private static final int TAB_ASSESSMENT = 0;
	private static final int TAB_APIS = 1;
	private static final int TAB_EVIDENCE = 2;
	private static final int TAB_AI = 3;
	private static final int TAB_OPPORTUNITIES = 4;
	private final MainWindow window;
	private final AiTaskRunner runner = new AiTaskRunner();
	private final JComboBox<AnalysisGoal> goal = new JComboBox<>(AnalysisGoal.values());
	private final JTextField question = new JTextField();
	private final JCheckBox useAi = new JCheckBox(AiText.str("analysis.use_ai"));
	private final JLabel destination = WorkbenchUi.mutedLabel("");
	private final JLabel coverage = WorkbenchUi.mutedLabel("");
	private final JLabel projectName = WorkbenchUi.title("", 3);
	private final JTextArea status = WorkbenchUi.body("");
	private final JProgressBar progress = new JProgressBar();
	private final JButton start = button("analysis.start", () -> start(false));
	private final JButton rescan = button("analysis.rescan", () -> start(true));
	private final JButton stop = button("stop", this::cancel);
	private final JButton settings = button("settings", this::settings);
	private final JButton explain = button("analysis.explain", () -> analyze(null));
	private final JButton deepen = button("analysis.deepen", () -> analyze(this.findings.getSelectedValue()));
	private final JButton export = button("export", this::export);
	private final JButton review = button("investigate", this::review);
	private final JButton source = button("analysis.source", this::openSelected);
	private final JButton reveal = button("analysis.reveal", this::reveal);
	private final JComboBox<String> filter = new JComboBox<>();
	private final DefaultListModel<AnalysisFinding> items = new DefaultListModel<>();
	private final JList<AnalysisFinding> findings = new JList<>(items);
	private final JEditorPane narrative = new JEditorPane("text/html", "");
	private final JTextArea detail = new JTextArea();
	private final JTabbedPane results = new JTabbedPane();
	private final ApiCatalogPanel apiCatalog;
	private final AssessmentPanel assessment;
	private final OpportunityPanel opportunities;
	private final JPanel mission = WorkbenchUi.card(16);
	private final JButton toggleMission = button("assessment.settings_hide", () -> setMissionVisible(!mission.isVisible()));
	private AnalysisReport report;
	private AiRecord displayed;
	private boolean busy;
	private long epoch;
	private String taskQuestion = "";
	private AnalysisGoal taskGoal = AnalysisGoal.EXPLORE;
	private boolean restoringSelection;
	private static final String[] GROUPS = { "all", "secret", "network", "behavior", "config" };

	public AnalysisPanel(MainWindow window) {
		super(new BorderLayout(0, 10));
		this.window = window;
		apiCatalog = new ApiCatalogPanel(this::open, endpoint -> {
			goal.setSelectedItem(AnalysisGoal.NETWORK);
			analyze(endpoint.asFinding());
		});
		assessment = new AssessmentPanel(this::open, (finding, selectedGoal) -> {
			goal.setSelectedItem(selectedGoal);
			analyze(finding);
		}, () -> window.getProject().setAnalysisReport(report), () -> results.setSelectedIndex(TAB_APIS), goal::setSelectedItem);
		opportunities = new OpportunityPanel(this::open, () -> {
			assessment.setReviewMode(false);
			goal.setSelectedItem(AnalysisGoal.EXPLORE);
			analyze(null);
		});
		goal.addActionListener(e -> setBusy(busy));
		setBorder(BorderFactory.createEmptyBorder(18, 22, 14, 22));
		setMinimumSize(new Dimension(420, 300));
		setOpaque(false);
		build();
		window.addLoadListener(loaded -> {
			if (!loaded) {
				runner.cancel();
				Runnable reset = () -> {
					epoch++;
					report = null;
					displayed = null;
					setBusy(false);
					items.clear();
					apiCatalog.setReport(null);
					assessment.setReport(null);
					opportunities.setReport(null);
					assessment.setReviewMode(false);
					setMissionVisible(true);
					results.setSelectedIndex(TAB_ASSESSMENT);
					renderOverview();
				};
				if (SwingUtilities.isEventDispatchThread()) {
					reset.run();
				} else {
					SwingUtilities.invokeLater(reset);
				}
			}
			return false;
		});
	}

	private void build() {
		JPanel top = WorkbenchUi.transparent();
		JPanel heading = WorkbenchUi.transparent();
		JPanel title = WorkbenchUi.transparent();
		title.add(WorkbenchUi.accentTitle(AiText.str("analysis.title"), 10), BorderLayout.WEST);
		title.add(projectName);
		heading.add(title);
		JPanel actions = flow();
		actions.add(toggleMission);
		actions.add(button("analysis.browse", window::focusWorkspaceExplorer));
		actions.add(export);
		heading.add(actions, BorderLayout.EAST);
		top.add(heading, BorderLayout.NORTH);
		mission.add(WorkbenchUi.body(AiText.str("analysis.intro")), BorderLayout.NORTH);
		JPanel input = WorkbenchUi.transparent();
		goal.setToolTipText(AiText.str("analysis.choose_goal"));
		goal.getAccessibleContext().setAccessibleName(AiText.str("analysis.choose_goal"));
		input.add(goal, BorderLayout.WEST);
		question.putClientProperty("JTextField.placeholderText", AiText.str("analysis.question"));
		question.getAccessibleContext().setAccessibleName(AiText.str("analysis.question"));
		question.addActionListener(e -> start(false));
		input.add(question);
		WorkbenchUi.primary(start);
		input.add(start, BorderLayout.EAST);
		mission.add(input);
		JPanel options = flow();
		useAi.setOpaque(false);
		options.add(useAi);
		options.add(destination);
		options.add(settings);
		mission.add(options, BorderLayout.SOUTH);
		top.add(mission);
		JPanel state = WorkbenchUi.transparent();
		state.add(coverage, BorderLayout.NORTH);
		status.setRows(1);
		state.add(status);
		progress.setPreferredSize(new Dimension(100, 4));
		state.add(progress, BorderLayout.SOUTH);
		top.add(state, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);

		narrative.setEditable(false);
		narrative.addHyperlinkListener(event -> {
			if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getDescription().startsWith("evidence:")
					&& displayed != null) {
				try {
					int index = Integer.parseInt(event.getDescription().substring(9));
					if (index >= 0 && index < displayed.getEvidence().size()) {
						open(displayed.getEvidence().get(index));
					}
				} catch (NumberFormatException ignored) {
				}
			}
		});
		results.addTab(AiText.str("assessment.title"), assessment);
		results.addTab(AiText.str("api.title"), apiCatalog);
		JPanel discoveries = WorkbenchUi.transparent();
		JPanel listPanel = WorkbenchUi.transparent();
		for (String group : GROUPS) {
			filter.addItem(AiText.str("analysis.group." + group));
		}
		filter.getAccessibleContext().setAccessibleName(AiText.str("analysis.filter"));
		filter.addActionListener(e -> refreshFindings());
		listPanel.add(filter, BorderLayout.NORTH);
		findings.setCellRenderer((list, value, index, selected, focused) -> {
			JPanel row = new JPanel(new BorderLayout(0, 5));
			row.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
			row.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
			JLabel name = WorkbenchUi.title(value.getId() + " · " + value.getTitle(), 0);
			JLabel location =
					new JLabel(value.getKind().group().equals("network") ? value.getValue() : value.getEvidence().get(0).getTitle());
			location.putClientProperty("html.disable", true);
			location.setFont(location.getFont().deriveFont(location.getFont().getSize2D() - 1));
			name.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
			location.setForeground(selected ? list.getSelectionForeground() : WorkbenchUi.muted());
			row.add(name, BorderLayout.NORTH);
			row.add(location);
			row.setToolTipText(value.toString());
			row.setPreferredSize(new Dimension(180, 64));
			return row;
		});
		findings.setFixedCellHeight(64);
		findings.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		findings.getAccessibleContext().setAccessibleName(AiText.str("analysis.findings"));
		findings.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && !restoringSelection) {
				renderDetail();
			}
		});
		listPanel.add(new JScrollPane(findings));
		detail.setEditable(false);
		detail.setLineWrap(true);
		detail.setWrapStyleWord(true);
		detail.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
		detail.setFont(UIManager.getFont("Label.font"));
		JPanel detailPanel = WorkbenchUi.transparent();
		detailPanel.add(new JScrollPane(detail));
		JPanel detailActions = flow();
		WorkbenchUi.primary(deepen);
		detailActions.add(deepen);
		detailActions.add(source);
		detailActions.add(reveal);
		detailPanel.add(detailActions, BorderLayout.SOUTH);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, detailPanel);
		split.setBorder(null);
		split.setResizeWeight(0.38);
		split.setDividerLocation(310);
		listPanel.setMinimumSize(new Dimension(160, 150));
		detailPanel.setMinimumSize(new Dimension(220, 150));
		discoveries.add(split);
		results.addTab(AiText.str("analysis.findings"), discoveries);
		results.addTab(AiText.str("analysis.conclusions"), new JScrollPane(narrative));
		results.addTab(AiText.str("opportunity.title"), opportunities);
		results.addChangeListener(e -> setBusy(busy));
		add(results);
		JPanel footer = flow();
		footer.add(stop);
		footer.add(explain);
		footer.add(review);
		footer.add(rescan);
		add(footer, BorderLayout.SOUTH);
		setBusy(false);
		renderOverview();
	}

	private void setMissionVisible(boolean visible) {
		mission.setVisible(visible);
		toggleMission.setText(AiText.str(visible ? "assessment.settings_hide" : "assessment.settings_show"));
		revalidate();
		repaint();
	}

	public void refreshProject() {
		cancel();
		projectName.setText(
				window.getProject().getFilePaths().stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(" · ")));
		report = window.getProject().getAnalysisReport();
		displayed = report == null ? null : report.getAnalysis();
		refreshConnection();
		refreshFindings();
		setMissionVisible(report == null || !report.isComplete());
		results.setSelectedIndex(TAB_ASSESSMENT);
		status.setText(AiText.str(report == null ? "analysis.ready" : "analysis.restored"));
		renderOverview();
	}

	private void refreshConnection() {
		AiProfile profile = profile();
		boolean configured = profile != null && !profile.getUrl().isBlank() && !profile.getModel().isBlank();
		useAi.setEnabled(configured && !busy);
		useAi.setSelected(configured);
		destination.setText(configured ? profile.getName() + " · " + profile.destination() : AiText.str("analysis.local_only"));
		destination.setToolTipText(AiText.str("analysis.data_hint"));
	}

	private AiProfile profile() {
		return AiConfiguration.from(window.getSettings()).forTask("audit");
	}

	private void settings() {
		new AiSettingDialog(window, window.getSettings()).setVisible(true);
		refreshConnection();
	}

	private void start(boolean force) {
		if (busy || !window.isProjectLoaded()) {
			return;
		}
		if (question.getText().length() > 2000) {
			status.setText(AiText.str("analysis.question_limit"));
			return;
		}
		taskQuestion = question.getText().trim();
		taskGoal = (AnalysisGoal) goal.getSelectedItem();
		boolean withAi = useAi.isSelected();
		JadxProject project = window.getProject();
		JadxDecompiler decompiler = window.getWrapper().getDecompiler();
		// A cancelled rescan may leave a partial view; keep the last saved assessment as the review
		// baseline.
		AnalysisReport previous = report != null && report.isComplete() ? report : project.getAnalysisReport();
		long id = ++epoch;
		setBusy(true);
		status.setText(AiText.str("analysis.identifying"));
		displayed = null;
		if (taskGoal == AnalysisGoal.EXPLORE || taskGoal == AnalysisGoal.SECURITY) {
			assessment.setReviewMode(taskGoal == AnalysisGoal.SECURITY);
		}
		results.setSelectedIndex(taskGoal == AnalysisGoal.NETWORK ? TAB_APIS
				: taskGoal == AnalysisGoal.SECURITY || taskGoal == AnalysisGoal.EXPLORE ? TAB_ASSESSMENT : TAB_EVIDENCE);
		runner.submit(token -> {
			String fingerprint = ApkAnalysisScanner.fingerprint(project.getFilePaths(), token);
			if (!force && !fingerprint.isEmpty() && previous != null && previous.matches(fingerprint)) {
				return previous;
			}
			return new ApkAnalysisScanner(decompiler).scan(fingerprint, token, snapshot -> SwingUtilities.invokeLater(() -> {
				if (valid(id, project)) {
					report = snapshot;
					refreshFindings();
					status.setText(AiText.str("analysis.scanning"));
				}
			}));
		}, completed -> SwingUtilities.invokeLater(() -> {
			if (!valid(id, project)) {
				return;
			}
			report = completed;
			if (report != previous) {
				report.carryReviews(previous);
			}
			project.setAnalysisReport(report);
			setBusy(false);
			refreshFindings();
			renderOverview();
			status.setText(AiText.str("analysis.scan_complete"));
			setMissionVisible(false);
			if (withAi && report.hasEvidence()) {
				analyze(null);
			}
		}), error -> failure(id, project, error));
	}

	private void analyze(AnalysisFinding focus) {
		if (busy || report == null || !report.isComplete() || !report.hasEvidence()) {
			return;
		}
		if (question.getText().length() > 2000) {
			status.setText(AiText.str("analysis.question_limit"));
			return;
		}
		taskGoal = (AnalysisGoal) goal.getSelectedItem();
		taskQuestion = question.getText().trim();
		AiProfile profile = profile();
		if (profile == null || profile.getUrl().isBlank() || profile.getModel().isBlank()) {
			settings();
			return;
		}
		final OpenAiClient client;
		try {
			profile.validate();
			client = new OpenAiClient(profile.getUrl(), profile.resolveApiKey());
		} catch (IllegalArgumentException e) {
			status.setText(e.getMessage());
			return;
		}
		AiProfile selectedProfile = profile.copy();
		JadxProject project = window.getProject();
		JadxDecompiler decompiler = window.getWrapper().getDecompiler();
		AnalysisReport snapshot = report.snapshot();
		long id = ++epoch;
		setBusy(true);
		status.setText(AiText.str("analysis.planning"));
		runner.submit(token -> new AnalysisInvestigator(decompiler, client).investigate(snapshot, taskGoal, taskQuestion, focus,
				selectedProfile, token, stage -> SwingUtilities.invokeLater(() -> {
					if (valid(id, project)) {
						status.setText(stage);
					}
				})),
				completed -> SwingUtilities.invokeLater(() -> {
					if (!valid(id, project)) {
						return;
					}
					displayed = completed;
					report.setAnalysis(completed);
					project.setAnalysisReport(report);
					setBusy(false);
					renderOverview();
					opportunities.setReport(report);
					boolean hasOpportunities =
							completed.getOpportunities() != null && !completed.getOpportunities().getCandidates().isEmpty();
					results.setSelectedIndex(hasOpportunities ? TAB_OPPORTUNITIES : TAB_AI);
					status.setText(completed.getOpportunities() != null && !hasOpportunities
							? completed.getOpportunities().diagnostics()
							: AiText.str("analysis.ai_complete"));
				}), error -> failure(id, project, error));
	}

	private boolean valid(long id, JadxProject project) {
		return epoch == id && window.getProject() == project && window.isProjectLoaded();
	}

	private void failure(long id, JadxProject project, Exception error) {
		SwingUtilities.invokeLater(() -> {
			if (!valid(id, project)) {
				return;
			}
			setBusy(false);
			status.setText(AiText.str("analysis.failed") + AiConnectionError.describe(error));
			renderOverview();
		});
	}

	private void cancel() {
		epoch++;
		runner.cancel();
		setBusy(false);
		status.setText(AiText.str("analysis.stopped"));
	}

	public void close() {
		runner.close();
	}

	public void refreshTheme() {
		renderOverview();
	}

	private void refreshFindings() {
		apiCatalog.setReport(report);
		assessment.setReport(report);
		opportunities.setReport(report);
		String selected = findings.getSelectedValue() == null ? "" : findings.getSelectedValue().getId();
		String group = GROUPS[Math.max(0, filter.getSelectedIndex())];
		restoringSelection = true;
		items.clear();
		if (report != null) {
			report.getFindings().stream().filter(f -> group.equals("all") || f.getKind().group().equals(group))
					.sorted(Comparator.comparingInt(f -> f.priority(taskGoal))).forEach(items::addElement);
			coverage.setText(report.coverage());
		} else {
			coverage.setText(AiText.str("analysis.local_only"));
		}
		int index = 0;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).getId().equals(selected)) {
				index = i;
				break;
			}
		}
		if (!items.isEmpty()) {
			findings.setSelectedIndex(index);
		}
		restoringSelection = false;
		renderDetail();
		setBusy(busy);
	}

	private void renderDetail() {
		AnalysisFinding finding = findings.getSelectedValue();
		if (finding == null) {
			detail.setText(AiText.str("analysis.empty"));
		} else {
			StringBuilder text = new StringBuilder(finding.getId() + " · " + finding.getTitle() + "\n\n");
			text.append(finding.getKind().text("why")).append("\n\n").append(AiText.str("analysis.unknown"))
					.append("\n").append(finding.getKind().text("gap")).append("\n\n")
					.append(AiText.str("analysis.next")).append("\n").append(finding.getKind().text("next")).append("\n\n")
					.append(String.format(AiText.str("analysis.occurrences"), finding.getOccurrences())).append("\n");
			for (AiEvidence evidence : finding.getEvidence()) {
				text.append("\n— ").append(evidence.getTitle()).append(" —\n").append(evidence.getCode()).append('\n');
			}
			detail.setText(text.toString());
		}
		detail.setCaretPosition(0);
		setBusy(busy);
	}

	private void renderOverview() {
		String text;
		if (displayed != null) {
			text = displayed.getNarrative();
		} else if (report == null) {
			text = "# " + AiText.str("analysis.welcome") + "\n\n" + AiText.str("analysis.intro") + "\n\n" + AiText.str("analysis.steps");
		} else {
			text = "# " + AiText.str(!report.hasEvidence() ? "analysis.no_evidence" : "analysis.local_result") + "\n\n"
					+ report.coverage() + "\n\n" + AiText.str(report.isComplete() ? "analysis.scan_complete" : "analysis.partial") + "\n\n"
					+ AiText.str("analysis.local_result_hint");
		}
		text += "\n\n---\n\n" + AiText.str("analysis.limits");
		if (report != null && !report.getGaps().isEmpty()) {
			text += "\n\n" + String.join("\n\n", report.getGaps());
		}
		String html = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(Parser.builder().build().parse(text))
				.replaceAll("(?is)<img\\b[^>]*>", "");
		if (displayed != null) {
			for (int i = 0; i < displayed.getEvidence().size(); i++) {
				html = html.replace("[S" + (i + 1) + "]", "<a href='evidence:" + i + "'>[S" + (i + 1) + "]</a>");
			}
		}
		java.awt.Color color = UIManager.getColor("TextArea.foreground");
		String foreground = String.format("#%06x", color == null ? 0x222222 : color.getRGB() & 0xffffff);
		narrative.setText("<html><body style='font-family:sans-serif;padding:18px;color:" + foreground + "'>" + html + "</body></html>");
		narrative.setCaretPosition(0);
	}

	private void setBusy(boolean value) {
		busy = value;
		boolean hasFindings = report != null && report.isComplete() && report.hasEvidence();
		apiCatalog.setBusy(value);
		assessment.setBusy(value);
		opportunities.setBusy(value);
		explain.setText(AiText.str(goal.getSelectedItem() == AnalysisGoal.EXPLORE || goal.getSelectedItem() == AnalysisGoal.SECURITY
				? "opportunity.compare"
				: "analysis.explain"));
		start.setEnabled(!value);
		rescan.setEnabled(!value && report != null);
		stop.setEnabled(value);
		stop.setVisible(value);
		goal.setEnabled(!value);
		question.setEditable(!value);
		settings.setEnabled(!value);
		AiProfile profile = profile();
		useAi.setEnabled(!value && profile != null && !profile.getUrl().isBlank() && !profile.getModel().isBlank());
		explain.setEnabled(!value && hasFindings);
		explain.setVisible(results.getSelectedIndex() != TAB_OPPORTUNITIES);
		deepen.setEnabled(!value && hasFindings && findings.getSelectedValue() != null);
		source.setEnabled(findings.getSelectedValue() != null);
		reveal.setEnabled(findings.getSelectedValue() != null && findings.getSelectedValue().getRawValue() != null);
		export.setEnabled(report != null && !value);
		review.setEnabled(!value && displayed != null && !displayed.getFindings().isEmpty());
		review.setVisible(results.getSelectedIndex() == TAB_AI && displayed != null && !displayed.getFindings().isEmpty());
		progress.setIndeterminate(value);
		progress.setVisible(value);
	}

	private void openSelected() {
		if (findings.getSelectedValue() != null) {
			open(findings.getSelectedValue().getEvidence().get(0));
		}
	}

	private void open(AiEvidence evidence) {
		if (evidence.getNode() != null) {
			JavaNode node = AiContext.resolve(window.getWrapper().getDecompiler(), evidence.getNode());
			if (node != null) {
				window.showWorkspaceExplorer();
				window.getTabsController().codeJump(window.getCacheObject().getNodeCache().makeFrom(node));
				return;
			}
		} else {
			for (ResourceFile resource : window.getWrapper().getResources()) {
				if (evidence.getTitle().startsWith(resource.getDeobfName() + ':') || evidence.getTitle().equals(resource.getDeobfName())) {
					window.showWorkspaceExplorer();
					window.getTabsController().codeJump(new JResource(resource, resource.getDeobfName(), JResource.JResType.FILE));
					return;
				}
			}
		}
		showText(evidence.getTitle(), evidence.getCode());
	}

	private void reveal() {
		AnalysisFinding finding = findings.getSelectedValue();
		if (finding != null && finding.getRawValue() != null) {
			showText(AiText.str("analysis.local_value"), finding.getRawValue());
		}
	}

	private void showText(String title, String text) {
		JTextArea view = new JTextArea(text, 14, 65);
		view.setEditable(false);
		view.setLineWrap(true);
		view.setWrapStyleWord(true);
		view.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
		view.setCaretPosition(0);
		JOptionPane.showMessageDialog(window, new JScrollPane(view), title, JOptionPane.PLAIN_MESSAGE);
	}

	private void review() {
		if (displayed != null) {
			new AiInvestigationDialog(window, displayed, () -> {
				window.getProject().setAnalysisReport(report);
				renderOverview();
			}, this::open).setVisible(true);
		}
	}

	private void export() {
		if (report == null) {
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter("Markdown", "md"));
		chooser.setSelectedFile(new java.io.File("apk-analysis.md"));
		if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		java.nio.file.Path path = chooser.getSelectedFile().toPath();
		if (Files.exists(path) && JOptionPane.showConfirmDialog(window, AiText.str("analysis.overwrite"), AiText.str("export"),
				JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			Files.writeString(path, report.markdown(), StandardCharsets.UTF_8);
			status.setText(AiText.str("analysis.exported") + path);
		} catch (Exception e) {
			status.setText(AiText.str("failed") + e.getMessage());
		}
	}

	private static JPanel flow() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 2));
		panel.setOpaque(false);
		return panel;
	}

	private static JButton button(String key, Runnable action) {
		JButton button = new JButton(AiText.str(key));
		button.addActionListener(e -> action.run());
		return button;
	}
}
