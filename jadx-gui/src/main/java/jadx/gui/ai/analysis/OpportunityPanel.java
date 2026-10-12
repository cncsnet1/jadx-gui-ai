package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiRecord;
import jadx.gui.ai.AiText;
import jadx.gui.ai.analysis.OpportunityAnalysis.Candidate;
import jadx.gui.ai.analysis.OpportunityAnalysis.Citation;
import jadx.gui.ai.analysis.OpportunityAnalysis.Priority;
import jadx.gui.utils.ui.WorkbenchUi;

/** A small, source-backed shortlist; it never computes exploitability from local keyword hits. */
public final class OpportunityPanel extends JPanel {
	private final DefaultListModel<Candidate> model = new DefaultListModel<>();
	private final JList<Candidate> list = new JList<>(model);
	private final JTextArea scope = WorkbenchUi.body("");
	private final JLabel counts = WorkbenchUi.mutedLabel("");
	private final JTextArea detail = new JTextArea();
	private final JComboBox<Citation> citations = new JComboBox<>();
	private final JButton source = new JButton(AiText.str("analysis.source"));
	private final JButton compare = new JButton(AiText.str("opportunity.compare"));
	private AiRecord record;
	private boolean ready;

	public OpportunityPanel(Consumer<AiEvidence> open, Runnable investigate) {
		super(new BorderLayout(10, 12));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
		JPanel heading = WorkbenchUi.transparent();
		JPanel title = WorkbenchUi.transparent();
		title.add(WorkbenchUi.accentTitle(AiText.str("opportunity.heading"), 6), BorderLayout.WEST);
		WorkbenchUi.primary(compare);
		compare.addActionListener(e -> investigate.run());
		title.add(compare, BorderLayout.EAST);
		heading.add(title, BorderLayout.NORTH);
		heading.add(scope);
		heading.add(counts, BorderLayout.SOUTH);
		add(heading, BorderLayout.NORTH);

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(96);
		list.getAccessibleContext().setAccessibleName(AiText.str("opportunity.title"));
		list.setCellRenderer((view, item, index, selected, focused) -> {
			JPanel card = new JPanel(new BorderLayout(8, 8));
			card.setBackground(selected ? WorkbenchUi.tint() : view.getBackground());
			card.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(0, selected ? 3 : 1, 1, 0, selected ? WorkbenchUi.accent() : WorkbenchUi.line()),
					BorderFactory.createEmptyBorder(12, 12, 12, 12)));
			JLabel rank =
					WorkbenchUi.accentTitle(String.format("%02d", index + 1) + "  " + OpportunityAnalysis.label(item.getPriority()), 0);
			card.add(rank, BorderLayout.NORTH);
			JLabel name = WorkbenchUi.title(item.getLeadId() + " · " + item.getTitle(), 1);
			card.add(name);
			card.add(WorkbenchUi.mutedLabel(OpportunityAnalysis.label(item.getAccess()) + " · "
					+ OpportunityAnalysis.label(item.getEffort())), BorderLayout.SOUTH);
			// A trusted ID prefix keeps model titles from enabling Swing's HTML tooltip renderer.
			card.setToolTipText(item.getLeadId() + " · " + item.getTitle());
			return card;
		});
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				renderDetail();
			}
		});
		JPanel shortlist = WorkbenchUi.transparent();
		shortlist.add(new JScrollPane(list));
		shortlist.setMinimumSize(new Dimension(250, 160));

		detail.setEditable(false);
		detail.setLineWrap(true);
		detail.setWrapStyleWord(true);
		detail.setFont(UIManager.getFont("Label.font"));
		detail.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
		detail.getAccessibleContext().setAccessibleName(AiText.str("opportunity.details"));
		JPanel reading = WorkbenchUi.transparent();
		reading.add(new JScrollPane(detail));
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
		actions.setOpaque(false);
		citations.getAccessibleContext().setAccessibleName(AiText.str("opportunity.quotes"));
		citations.addActionListener(e -> source.setEnabled(record != null && citations.getSelectedItem() != null));
		actions.add(citations);
		actions.add(source);
		reading.add(actions, BorderLayout.SOUTH);
		reading.setMinimumSize(new Dimension(290, 160));
		source.addActionListener(e -> {
			Citation citation = (Citation) citations.getSelectedItem();
			if (record != null && citation != null) {
				AiEvidence evidence = citation.resolve(record.getEvidence());
				if (evidence != null) {
					open.accept(evidence);
				}
			}
		});
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, shortlist, reading);
		split.setBorder(null);
		split.setResizeWeight(0.36);
		split.setDividerLocation(360);
		split.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent event) {
				split.setDividerLocation(0.36);
			}
		});
		add(split);
		setReport(null);
	}

	public void setReport(AnalysisReport report) {
		AiRecord next = report == null ? null : report.getAnalysis();
		ready = report != null && report.isComplete() && report.hasEvidence();
		if (record == next && next != null) {
			return;
		}
		record = next;
		model.clear();
		OpportunityAnalysis analysis = record == null ? null : record.getOpportunities();
		if (analysis == null) {
			scope.setText(AiText.str("opportunity.empty"));
			counts.setText(AiText.str("opportunity.boundary"));
		} else {
			scope.setText(analysis.scope());
			analysis.getCandidates().forEach(model::addElement);
			counts.setText(String.format(AiText.str("opportunity.counts"), count(analysis, Priority.FIRST),
					count(analysis, Priority.INVESTIGATE), count(analysis, Priority.DEFER)));
		}
		if (!model.isEmpty()) {
			list.setSelectedIndex(0);
		}
		renderDetail();
		setBusy(false);
	}

	public void setBusy(boolean busy) {
		compare.setEnabled(!busy && ready);
	}

	private static long count(OpportunityAnalysis analysis, Priority priority) {
		return analysis.getCandidates().stream().filter(c -> c.getPriority() == priority).count();
	}

	private void renderDetail() {
		Candidate selected = list.getSelectedValue();
		citations.removeAllItems();
		OpportunityAnalysis analysis = record == null ? null : record.getOpportunities();
		String diagnostic = analysis == null ? "" : analysis.diagnostics();
		if (selected == null) {
			detail.setText(diagnostic.isEmpty() ? AiText.str("opportunity.empty_detail") : diagnostic);
		} else {
			detail.setText((diagnostic.isEmpty() ? "" : diagnostic + "\n\n") + selected.detail());
			selected.getCitations().forEach(citations::addItem);
		}
		source.setEnabled(selected != null && citations.getItemCount() > 0);
		citations.setEnabled(citations.getItemCount() > 0);
		detail.setCaretPosition(0);
	}
}
