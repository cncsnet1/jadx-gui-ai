package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
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
	private final JTextArea code = new JTextArea();
	private final JButton resume = new JButton(AiText.str("investigation.continue"));
	private final JButton source = new JButton(AiText.str("analysis.source"));
	private final Consumer<AiRecord> display;
	private final JTabbedPane views = new JTabbedPane();
	private boolean refreshing;
	private boolean busy;
	private AiRecord legacy;

	public InvestigationPanel(JEditorPane narrative, OpportunityPanel opportunities, Consumer<InvestigationRun> continueRun,
			Consumer<AiRecord> display, Consumer<AiEvidence> open) {
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
		top.add(history);
		WorkbenchUi.primary(resume);
		resume.addActionListener(e -> {
			if (selected() != null) {
				continueRun.accept(selected());
			}
		});
		top.add(resume, BorderLayout.EAST);
		top.add(state, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);
		for (JTextArea area : List.of(timeline, code)) {
			area.setEditable(false);
			area.setLineWrap(true);
			area.setWrapStyleWord(true);
			area.setFont(UIManager.getFont("Label.font"));
			area.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		}
		views.addTab(AiText.str("investigation.progress"), new JScrollPane(timeline));
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
		add(views);
	}

	public InvestigationRun selected() {
		return (InvestigationRun) history.getSelectedItem();
	}

	public void setReport(AnalysisReport report, String selectId) {
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
		views.setSelectedIndex(0);
	}

	public void showAnswer(boolean opportunities) {
		views.setSelectedIndex(opportunities ? 2 : 1);
	}

	public void setBusy(boolean value) {
		busy = value;
		resume.setEnabled(!value && selected() != null);
	}

	private void render() {
		InvestigationRun run = selected();
		String selectedEvidence = evidence.getSelectedValue() == null ? null : evidence.getSelectedValue().getId();
		entries.clear();
		if (run == null) {
			state.setText(AiText.str(legacy == null ? "investigation.empty" : "investigation.legacy"));
			timeline.setText(AiText.str("investigation.empty"));
			display.accept(legacy);
		} else {
			state.setText(run.getState() + " · " + run.getReason()
					+ (run.getAnswer() == null
							? ""
							: "\n" + String.format(AiText.str("investigation.answer_steps"), run.getAnsweredSteps())));
			StringBuilder text = new StringBuilder(run.toString() + "\n\n");
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
