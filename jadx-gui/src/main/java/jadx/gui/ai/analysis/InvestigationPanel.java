package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiRecord;
import jadx.gui.ai.AiText;
import jadx.gui.utils.ui.WorkbenchUi;

/** History, immutable observations and an answer are views of the same investigation. */
public final class InvestigationPanel extends JPanel {
	private final JComboBox<InvestigationRun> history = new JComboBox<>();
	private final JTextArea state = WorkbenchUi.body(AiText.str("investigation.empty"));
	private final DefaultListModel<EvidenceStore.Entry> entries = new DefaultListModel<>();
	private final JList<EvidenceStore.Entry> evidence = new JList<>(entries);
	private final JTextArea timeline = new JTextArea();
	private final JPanel plan = WorkbenchUi.transparent();
	private final JPanel launcher = WorkbenchUi.card(16);
	private final JPanel continuation = WorkbenchUi.card(12);
	private final JPanel content = new JPanel(new CardLayout());
	private boolean composing = true;
	private final JTextField task = new JTextField();
	private final JTextField followUp = new JTextField();
	private final JComboBox<AnalysisGoal> taskGoal = new JComboBox<>(AnalysisGoal.values());
	private final JButton start = new JButton(AiText.str("agent.start"));
	private final JButton newTask = new JButton(AiText.str("agent.new"));
	private final JButton stop = new JButton(AiText.str("agent.stop"));
	private final JTextArea activity = WorkbenchUi.body("");
	private final java.util.List<JButton> presets = new java.util.ArrayList<>();
	private final JTextArea code = new JTextArea();
	private final JButton resume = new JButton(AiText.str("investigation.continue"));
	private final JButton source = new JButton(AiText.str("analysis.source"));
	private final Consumer<AiRecord> display;
	private final JTabbedPane views = new JTabbedPane();
	private boolean refreshing;
	private boolean busy;
	private AiRecord legacy;
	private boolean available;
	private String selectedId;
	private AnalysisReport currentReport;

	public InvestigationPanel(JEditorPane narrative, OpportunityPanel opportunities, BiConsumer<InvestigationRun, String> continueRun,
			BiConsumer<AnalysisGoal, String> startRun, Runnable cancel, Consumer<AiRecord> display, Consumer<AiEvidence> open) {
		super(new BorderLayout(10, 10));
		this.display = display;
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		DefaultListCellRenderer renderer = new DefaultListCellRenderer();
		renderer.putClientProperty("html.disable", true);
		history.setRenderer(renderer);
		history.getAccessibleContext().setAccessibleName(AiText.str("investigation.title"));
		history.addActionListener(e -> {
			if (!refreshing) {
				render();
			}
		});
		JPanel top = WorkbenchUi.transparent();

		JPanel heading = WorkbenchUi.transparent();
		heading.add(history);
		heading.add(newTask, BorderLayout.EAST);
		heading.add(state, BorderLayout.SOUTH);
		top.add(heading);
		launcher.add(WorkbenchUi.title(AiText.str("agent.heading"), 4), BorderLayout.NORTH);
		JPanel composer = WorkbenchUi.transparent();
		JPanel quick = new JPanel(new GridLayout(0, 2, 8, 8));
		quick.setOpaque(false);
		for (AnalysisGoal value : List.of(AnalysisGoal.NETWORK, AnalysisGoal.LOGIN, AnalysisGoal.SECRETS, AnalysisGoal.EXPLORE)) {
			JButton preset = new JButton(AiText.str("agent.preset." + value.name().toLowerCase(java.util.Locale.ROOT)));
			preset.addActionListener(e -> startRun.accept(value, ""));
			presets.add(preset);
			quick.add(preset);
		}
		JTextArea introduction = WorkbenchUi.body(AiText.str("agent.intro"));
		introduction.setRows(2);
		composer.add(introduction, BorderLayout.NORTH);
		composer.add(quick);
		JPanel input = WorkbenchUi.transparent();
		input.add(taskGoal, BorderLayout.WEST);
		task.putClientProperty("JTextField.placeholderText", AiText.str("agent.task"));
		task.getAccessibleContext().setAccessibleName(AiText.str("agent.task"));
		input.add(task);
		WorkbenchUi.primary(start);
		start.addActionListener(e -> startRun.accept((AnalysisGoal) taskGoal.getSelectedItem(), task.getText().trim()));
		task.addActionListener(e -> start.doClick());
		input.add(start, BorderLayout.EAST);
		composer.add(input, BorderLayout.SOUTH);
		launcher.add(composer);
		newTask.addActionListener(e -> compose(!composing));
		WorkbenchUi.primary(resume);
		resume.addActionListener(e -> {
			if (canResume()) {
				continueRun.accept(selected(), followUp.getText().trim());
			}
		});
		add(top, BorderLayout.NORTH);
		followUp.putClientProperty("JTextField.placeholderText", AiText.str("agent.followup"));
		followUp.getAccessibleContext().setAccessibleName(AiText.str("agent.followup"));
		followUp.addActionListener(e -> resume.doClick());
		continuation.add(followUp);
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		controls.setOpaque(false);
		controls.add(resume);
		stop.addActionListener(e -> cancel.run());
		controls.add(stop);
		continuation.add(controls, BorderLayout.EAST);
		add(continuation, BorderLayout.SOUTH);
		for (JTextArea area : List.of(timeline, code)) {
			area.setEditable(false);
			area.setLineWrap(true);
			area.setWrapStyleWord(true);
			area.setFont(UIManager.getFont("Label.font"));
			area.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		}
		JPanel progress = WorkbenchUi.transparent();
		JPanel board = WorkbenchUi.card(14);
		board.add(activity, BorderLayout.NORTH);
		plan.setLayout(new BoxLayout(plan, BoxLayout.Y_AXIS));
		board.add(plan);
		ApplicationReportPanel.Document document = new ApplicationReportPanel.Document();
		document.setLayout(new BorderLayout());
		document.add(board, BorderLayout.NORTH);
		JScrollPane boardScroll = new JScrollPane(document);
		boardScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		boardScroll.getVerticalScrollBar().setUnitIncrement(18);
		boardScroll.setBorder(null);
		progress.add(boardScroll);
		JPanel log = WorkbenchUi.transparent();
		JButton toggleLog = new JButton(AiText.str("agent.log"));
		JScrollPane logScroll = new JScrollPane(timeline);
		logScroll.setPreferredSize(new Dimension(300, 170));
		logScroll.setVisible(false);
		toggleLog.addActionListener(e -> {
			logScroll.setVisible(!logScroll.isVisible());
			revalidate();
		});
		log.add(toggleLog, BorderLayout.NORTH);
		log.add(logScroll);
		progress.add(log, BorderLayout.SOUTH);
		views.addTab(AiText.str("investigation.progress"), progress);
		views.addTab(AiText.str("analysis.conclusions"), new JScrollPane(narrative));
		views.addTab(AiText.str("opportunity.title"), opportunities);
		evidence.setFixedCellHeight(44);
		evidence.setFixedCellWidth(240);
		evidence.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		evidence.setCellRenderer((list, entry, index, selected, focused) -> {
			var component = renderer.getListCellRendererComponent(list,
					"E" + (index + 1) + " · " + entry.getSource().getTitle(), index, selected, focused);
			((javax.swing.JLabel) component).setToolTipText(entry.getSource().getTitle());
			return component;
		});
		evidence.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				selection();
			}
		});
		JPanel detail = WorkbenchUi.transparent();
		detail.add(new JScrollPane(code));
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
		actions.setOpaque(false);
		source.addActionListener(e -> {
			if (evidence.getSelectedValue() != null) {
				open.accept(evidence.getSelectedValue().getSource());
			}
		});
		actions.add(source);
		detail.add(actions, BorderLayout.SOUTH);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(evidence), detail);
		split.setBorder(null);
		split.setResizeWeight(0.35);
		split.setDividerLocation(280);
		split.getLeftComponent().setMinimumSize(new Dimension(170, 120));
		detail.setMinimumSize(new Dimension(220, 120));
		views.addTab(AiText.str("investigation.evidence"), split);
		ApplicationReportPanel.Document welcome = new ApplicationReportPanel.Document();
		welcome.setLayout(new BorderLayout());
		welcome.add(launcher, BorderLayout.NORTH);
		JScrollPane startScroll = new JScrollPane(welcome);
		startScroll.setBorder(null);
		startScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		content.setOpaque(false);
		content.add(startScroll, "start");
		content.add(views, "task");
		add(content);
	}

	private void compose(boolean value) {
		composing = value;
		continuation.setVisible(!value);
		((CardLayout) content.getLayout()).show(content, value ? "start" : "task");
		newTask.setText(AiText.str(value && selected() != null ? "agent.back" : "agent.new"));
	}

	public InvestigationRun selected() {
		return (InvestigationRun) history.getSelectedItem();
	}

	public void setReport(AnalysisReport report, String selectId) {
		currentReport = report;
		available = report != null && report.isComplete() && report.hasEvidence();
		String selected = selectId != null ? selectId : selected() == null ? null : selected().getId();
		legacy = report == null ? null : report.getAnalysis();
		refreshing = true;
		DefaultComboBoxModel<InvestigationRun> model = new DefaultComboBoxModel<>();
		if (report != null) {
			for (int i = report.getInvestigations().size() - 1; i >= 0; i--) {
				model.addElement(report.getInvestigations().get(i));
			}
		}
		history.setModel(model);
		for (int i = 0; i < model.getSize(); i++) {
			if (model.getElementAt(i).getId().equals(selected)) {
				history.setSelectedIndex(i);
				break;
			}
		}
		refreshing = false;
		render();
	}

	public void showProgress() {
		compose(false);
		followUp.setText("");
		views.setSelectedIndex(0);
	}

	public void showAnswer(boolean opportunities) {
		views.setSelectedIndex(opportunities ? 2 : 1);
	}

	public void setBusy(boolean value) {
		busy = value;
		boolean resumable = canResume();
		resume.setEnabled(!value && resumable);
		followUp.setEnabled(!value && resumable);
		resume.setToolTipText(selected() != null && !resumable ? AiText.str("assessment.stale") : null);
		history.setEnabled(!value);
		newTask.setEnabled(!value && available);
		start.setEnabled(!value && available);
		presets.forEach(b -> b.setEnabled(!value && available));
		task.setEnabled(!value && available);
		taskGoal.setEnabled(!value && available);
		stop.setEnabled(value);
	}

	private boolean canResume() {
		return InvestigationService.canResume(currentReport, selected(), currentReport == null ? null : currentReport.getEngineProfile());
	}

	private void render() {
		InvestigationRun run = selected();
		resume.setText(AiText.str(run != null && run.getState() == InvestigationRun.State.FAILED
				? "agent.retry"
				: "investigation.continue"));
		String id = run == null ? null : run.getId();
		if (!java.util.Objects.equals(selectedId, id)) {
			followUp.setText("");
			compose(run == null);
			selectedId = id;
		}
		plan.removeAll();
		String selectedEvidence = evidence.getSelectedValue() == null ? null : evidence.getSelectedValue().getId();
		entries.clear();
		if (run == null) {
			state.setText(AiText.str(legacy == null ? "investigation.empty" : "investigation.legacy"));
			timeline.setText(AiText.str("investigation.empty"));
			activity.setText(AiText.str("agent.empty_plan"));
			compose(true);
			display.accept(legacy);
		} else {
			state.setText(run.getState() + " · " + run.getReason()
					+ (!busy && !canResume() ? "\n" + AiText.str("assessment.stale") : "")
					+ (run.getAnswer() == null
							? ""
							: "\n" + String.format(AiText.str("investigation.answer_steps"), run.getAnsweredSteps()))
					+ (run.getAnswer() != null && run.getAnsweredFollowUps() < run.getFollowUps().size()
							? "\n" + AiText.str("agent.answer_previous")
							: ""));
			StringBuilder text = new StringBuilder(run.toString() + "\n\n");
			run.getFollowUps().forEach(q -> text.append(AiText.str("agent.direction")).append(q).append("\n\n"));
			activity.setText((run.getState() == InvestigationRun.State.RUNNING ? run.getAction() + "\n" : "")
					+ String.format(AiText.str("agent.counts"), run.getRounds(), InvestigationService.MAX_ROUNDS,
							run.getCalls(), InvestigationService.MAX_CALLS, run.getEvidence().entries().size())
					+ (run.isCompatibility() ? "\n" + AiText.str("agent.compatibility") : "")
					+ (run.getFollowUps().isEmpty()
							? ""
							: "\n" + AiText.str("agent.direction")
									+ jadx.gui.ai.AiCodeChunks.prefix(run.getFollowUps().get(run.getFollowUps().size() - 1), 300)));
			if (run.getTasks().isEmpty()) {
				JTextArea empty = WorkbenchUi.body(AiText.str(run.getState() == InvestigationRun.State.RUNNING
						? "agent.awaiting_plan"
						: "agent.no_plan"));
				empty.setAlignmentX(LEFT_ALIGNMENT);
				plan.add(empty);
			}
			for (InvestigationRun.Task item : run.getTasks()) {
				JPanel row = WorkbenchUi.card(10);
				row.add(WorkbenchUi.body(AiText.str("agent.task." + item.getStatus()) + "  ·  " + item.getTitle()));
				if (!item.getEvidence().isEmpty()) {
					JButton proof = new JButton(String.format(AiText.str("agent.proof"), item.getEvidence().size()));
					proof.addActionListener(e -> {
						for (int i = 0; i < entries.size(); i++) {
							if (entries.get(i).getId().equals(item.getEvidence().get(0))) {
								evidence.setSelectedIndex(i);
								views.setSelectedIndex(3);
								break;
							}
						}
					});
					row.add(proof, BorderLayout.EAST);
				}
				row.setAlignmentX(LEFT_ALIGNMENT);
				plan.add(row);
				plan.add(javax.swing.Box.createVerticalStrut(6));
			}
			if (!run.getQuestions().isEmpty()) {
				JTextArea questions =
						WorkbenchUi.body(AiText.str("investigation.questions") + "\n" + String.join("\n", run.getQuestions()));
				questions.setAlignmentX(LEFT_ALIGNMENT);
				plan.add(questions);
			}
			int index = 0;
			for (InvestigationRun.Step step : run.getSteps()) {
				text.append(++index).append(". ").append(AiText.str("investigation.tool." + step.getOperation())).append(" · ")
						.append(step.getTarget()).append('\n').append(step.getSummary()).append("\n\n");
			}
			if (!run.getQuestions().isEmpty()) {
				text.append(AiText.str("investigation.questions")).append("\n");
				run.getQuestions().forEach(q -> text.append("• ").append(q).append('\n'));
			}
			timeline.setText(text.toString());
			timeline.setCaretPosition(0);
			entries.addAll(run.getEvidence().entries());
			display.accept(run.getAnswer());
		}
		if (!entries.isEmpty()) {
			int index = 0;
			for (int i = 0; i < entries.size(); i++) {
				if (entries.get(i).getId().equals(selectedEvidence)) {
					index = i;
					break;
				}
			}
			evidence.setSelectedIndex(index);
		}
		selection();
		plan.revalidate();
		plan.repaint();
		setBusy(busy);
	}

	private void selection() {
		EvidenceStore.Entry entry = evidence.getSelectedValue();
		code.setText(entry == null
				? AiText.str("investigation.empty")
				: entry.getSource().getTitle() + "\n" + entry.getId() + "\n" + entry.getOperation() + "\n\n"
						+ entry.getSource().getCode() + (entry.getSource().isTruncated() ? "\n\n" + AiText.str("api.truncated") : ""));
		code.setCaretPosition(0);
		source.setEnabled(entry != null);
	}
}
