package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.utils.ui.WorkbenchUi;

/**
 * The assessment entry point: observed surface, a triage queue, and separate human verification.
 */
public final class AssessmentPanel extends JPanel {
	private static final String[] GROUPS = { "all", "secret", "network", "behavior", "config" };
	private final Consumer<AiEvidence> open;
	private final BiConsumer<AnalysisFinding, AnalysisGoal> investigate;
	private final Runnable save;
	private final JLabel[] counts = new JLabel[4];
	private final JTextArea guide = WorkbenchUi.body(AiText.str("assessment.intro"));
	private final JLabel shown = WorkbenchUi.mutedLabel("");
	private final JButton gaps = new JButton();
	private final JComboBox<String> mode = new JComboBox<>();
	private final JButton[] metricActions = new JButton[4];
	private final JComboBox<String> group = new JComboBox<>();
	private final JComboBox<Object> state = new JComboBox<>();
	private final JTextField search = new JTextField();
	private final QueueModel model = new QueueModel();
	private final JTable table = new JTable(model);
	private final JTextArea detail = new JTextArea();
	private final JButton record = new JButton(AiText.str("assessment.record"));
	private final JButton trace = new JButton(AiText.str("assessment.trace"));
	private final JButton source = new JButton(AiText.str("analysis.source"));
	private final JButton handoff = new JButton(AiText.str("explore.export"));
	private AnalysisReport report;
	private List<AssessmentItem> items = List.of();
	private boolean busy;
	private boolean updating;

	public AssessmentPanel(Consumer<AiEvidence> open, BiConsumer<AnalysisFinding, AnalysisGoal> investigate, Runnable save,
			Runnable showApis, Consumer<AnalysisGoal> chooseGoal) {
		super(new BorderLayout(10, 8));
		this.open = open;
		this.investigate = investigate;
		this.save = save;
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
		JPanel top = WorkbenchUi.transparent();
		JPanel metrics = new JPanel(new GridLayout(1, 4, 10, 0));
		metrics.setOpaque(false);
		String[] labels = { "secrets", "apis", "hosts", "pending" };
		for (int i = 0; i < labels.length; i++) {
			JPanel metric = WorkbenchUi.card(12);
			counts[i] = WorkbenchUi.accentTitle("—", 14);
			metric.add(counts[i], BorderLayout.WEST);
			JButton label = new JButton(AiText.str("assessment.metric." + labels[i]));
			metricActions[i] = label;
			label.putClientProperty("JButton.buttonType", "borderless");
			label.setHorizontalAlignment(JButton.LEADING);
			if (i == 0) {
				label.addActionListener(e -> {
					search.setText("");
					state.setSelectedIndex(0);
					group.setSelectedIndex(1);
				});
			} else if (i == 3) {
				label.addActionListener(e -> {
					search.setText(isReviewMode() ? "" : ExplorationPath.Direction.ENTRY.toString());
					group.setSelectedIndex(0);
					if (isReviewMode()) {
						state.setSelectedItem(AssessmentReview.Status.UNREVIEWED);
					}
				});
			} else {
				label.addActionListener(e -> showApis.run());
			}
			metric.add(label);
			metrics.add(metric);
		}
		top.add(metrics, BorderLayout.NORTH);
		guide.setRows(1);
		top.add(guide);
		add(top, BorderLayout.NORTH);

		JPanel queue = WorkbenchUi.transparent();
		JPanel filters = WorkbenchUi.transparent();
		JPanel selections = flow();
		mode.addItem(AiText.str("explore.mode"));
		mode.addItem(AiText.str("explore.review_mode"));
		mode.getAccessibleContext().setAccessibleName(AiText.str("explore.mode_label"));
		selections.add(mode);
		for (String value : GROUPS) {
			group.addItem(AiText.str("analysis.group." + value));
		}
		group.getAccessibleContext().setAccessibleName(AiText.str("analysis.filter"));
		state.addItem(AiText.str("assessment.all_states"));
		for (AssessmentReview.Status value : AssessmentReview.Status.values()) {
			state.addItem(value);
		}
		state.getAccessibleContext().setAccessibleName(AiText.str("assessment.state"));
		selections.add(group);
		selections.add(state);
		filters.add(selections, BorderLayout.WEST);
		search.putClientProperty("JTextField.placeholderText", AiText.str("assessment.search"));
		search.getAccessibleContext().setAccessibleName(AiText.str("assessment.search"));
		filters.add(search);
		queue.add(filters, BorderLayout.NORTH);
		table.setRowHeight(36);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setAutoCreateRowSorter(true);
		table.setFillsViewportHeight(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.getAccessibleContext().setAccessibleName(AiText.str("assessment.queue"));
		DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
			@Override
			public java.awt.Component getTableCellRendererComponent(JTable view, Object value, boolean selected,
					boolean focused, int row, int column) {
				super.getTableCellRendererComponent(view, value, selected, focused, row, column);
				putClientProperty("html.disable", true);
				setToolTipText(value == null ? null : value.toString());
				setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
				if (selected) {
					setBackground(WorkbenchUi.tint());
					setForeground(view.getForeground());
				}
				return this;
			}
		};
		table.setDefaultRenderer(Object.class, renderer);
		table.getColumnModel().getColumn(0).setPreferredWidth(80);
		table.getColumnModel().getColumn(1).setPreferredWidth(250);
		table.getColumnModel().getColumn(2).setPreferredWidth(110);
		table.getColumnModel().getColumn(3).setPreferredWidth(105);
		queue.add(new JScrollPane(table));
		queue.add(shown, BorderLayout.SOUTH);

		detail.setEditable(false);
		detail.setLineWrap(true);
		detail.setWrapStyleWord(true);
		detail.setFont(UIManager.getFont("Label.font"));
		detail.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
		JPanel evidence = WorkbenchUi.transparent();
		evidence.add(new JScrollPane(detail));
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, queue, evidence);
		split.setBorder(null);
		split.setResizeWeight(0.62);
		split.setDividerLocation(580);
		queue.setMinimumSize(new Dimension(370, 180));
		evidence.setMinimumSize(new Dimension(250, 180));
		add(split);
		JPanel bottom = WorkbenchUi.transparent();
		JPanel actions = flow();
		WorkbenchUi.primary(trace);
		actions.add(trace);
		actions.add(handoff);
		actions.add(record);
		actions.add(source);
		bottom.add(actions);
		bottom.add(gaps, BorderLayout.EAST);
		add(bottom, BorderLayout.SOUTH);
		mode.addActionListener(e -> {
			updating = true;
			model.fireTableStructureChanged();
			table.getColumnModel().getColumn(0).setPreferredWidth(85);
			table.getColumnModel().getColumn(1).setPreferredWidth(250);
			table.getColumnModel().getColumn(2).setPreferredWidth(110);
			table.getColumnModel().getColumn(3).setPreferredWidth(105);
			updating = false;
			setReport(report);
			chooseGoal.accept(isReviewMode() ? AnalysisGoal.SECURITY : AnalysisGoal.EXPLORE);
		});
		group.addActionListener(e -> filter());
		state.addActionListener(e -> filter());
		search.getDocument().addDocumentListener(new DocumentListener() {
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
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && !updating) {
				select();
			}
		});
		record.addActionListener(e -> editReview());
		trace.addActionListener(e -> {
			if (selected() != null) {
				investigate.accept(selected().finding(), isReviewMode() ? AnalysisGoal.SECURITY : AnalysisGoal.EXPLORE);
			}
		});
		handoff.addActionListener(e -> exportPath());
		source.addActionListener(e -> {
			if (selected() != null) {
				open.accept(selected().source());
			}
		});
		gaps.addActionListener(e -> {
			JTextArea text = WorkbenchUi.body(AiText.str("analysis.limits") + "\n\n"
					+ (report == null ? AiText.str("analysis.ready") : report.coverage() + "\n\n" + String.join("\n\n", report.getGaps())));
			JScrollPane scroll = new JScrollPane(text);
			scroll.setPreferredSize(new Dimension(600, 300));
			JOptionPane.showMessageDialog(this, scroll, AiText.str("assessment.scope"), JOptionPane.PLAIN_MESSAGE);
		});
		setReport(null);
	}

	public boolean isReviewMode() {
		return mode.getSelectedIndex() == 1;
	}

	public void setReviewMode(boolean review) {
		mode.setSelectedIndex(review ? 1 : 0);
	}

	public void setReport(AnalysisReport report) {
		this.report = report;
		items = AssessmentItem.from(report);
		if (report == null) {
			group.setSelectedIndex(0);
			state.setSelectedIndex(0);
			search.setText("");
			table.getRowSorter().setSortKeys(null);
		}
		state.setVisible(isReviewMode());
		record.setVisible(isReviewMode());
		counts[0].setText(report == null ? "—" : Long.toString(items.stream().filter(i -> i.group().equals("secret")).count()));
		counts[1].setText(report == null
				? "—"
				: Long.toString(report.getApis().stream()
						.filter(a -> a.getOrigin() == ApiEndpoint.Origin.DECLARATION).count()));
		counts[2].setText(report == null
				? "—"
				: Long.toString(report.getApis().stream().map(ApiEndpoint::getHost)
						.filter(h -> !h.isEmpty()).distinct().count()));
		metricActions[3].setText(AiText.str(isReviewMode() ? "assessment.metric.pending" : "explore.metric.entries"));
		counts[3].setText(report == null
				? "—"
				: Long.toString(items.stream()
						.filter(i -> isReviewMode()
								? report.review(i.key()).getStatus() == AssessmentReview.Status.UNREVIEWED
								: ExplorationPath.direction(i) == ExplorationPath.Direction.ENTRY)
						.count()));
		guide.setText(AiText.str(report == null ? "assessment.intro" : isReviewMode() ? "assessment.boundary" : "explore.intro"));
		gaps.setText(AiText.str("assessment.scope") + (report == null ? "" : " · " + report.getGaps().size()));
		filter();
	}

	public void setBusy(boolean value) {
		busy = value;
		mode.setEnabled(!value);
		record.setEnabled(!value && report != null && report.isComplete() && selected() != null);
		trace.setEnabled(!value && report != null && report.isComplete() && selected() != null);
		source.setEnabled(selected() != null);
		handoff.setEnabled(!value && selected() != null);
	}

	private AssessmentItem selected() {
		int row = table.getSelectedRow();
		return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row));
	}

	private void filter() {
		AssessmentItem previous = selected();
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		String category = GROUPS[Math.max(0, group.getSelectedIndex())];
		Object status = state.getSelectedItem();
		updating = true;
		model.rows = items.stream().filter(i -> category.equals("all") || category.equals(i.group()))
				.filter(i -> !isReviewMode() || !(status instanceof AssessmentReview.Status)
						|| report.review(i.key()).getStatus() == status)
				.filter(i -> query.isEmpty() || (i.title() + '\n' + i.source().getTitle() + '\n' + ExplorationPath.direction(i))
						.toLowerCase(Locale.ROOT).contains(query))
				.sorted(isReviewMode()
						? Comparator.comparing(AssessmentItem::priority)
						: Comparator.comparing(ExplorationPath::direction)
								.thenComparingInt(i -> i.finding().getKind() == AnalysisFinding.Kind.HTTP_ROUTE ? 0
										: i.finding().getKind() == AnalysisFinding.Kind.ENDPOINT ? 2 : 1))
				.collect(java.util.stream.Collectors.toList());
		model.fireTableDataChanged();
		int selected = 0;
		for (int i = 0; previous != null && i < model.rows.size(); i++) {
			if (model.rows.get(i).key().equals(previous.key())) {
				selected = i;
				break;
			}
		}
		if (!model.rows.isEmpty()) {
			int view = table.convertRowIndexToView(selected);
			table.setRowSelectionInterval(view, view);
		}
		updating = false;
		shown.setText(String.format(AiText.str("assessment.shown"), model.rows.size(), items.size()));
		select();
	}

	private void select() {
		AssessmentItem selected = selected();
		if (selected == null) {
			detail.setText(AiText.str(report == null ? "assessment.empty" : "assessment.no_match"));
		} else if (!isReviewMode()) {
			detail.setText(ExplorationPath.detail(selected));
		} else {
			AssessmentReview review = report.review(selected.key());
			detail.setText(AiText.str("assessment.state") + ": " + review.getStatus() + "\n"
					+ AiText.str("assessment.severity") + ": " + review.getSeverity() + "\n\n" + selected.detail()
					+ (review.getNote().isEmpty() ? "" : "\n\n" + AiText.str("assessment.note") + "\n" + review.getNote()));
		}
		detail.setCaretPosition(0);
		setBusy(busy);
	}

	private void exportPath() {
		AssessmentItem item = selected();
		if (item == null || report == null || busy) {
			return;
		}
		// Capture before the chooser's nested event loop can change selection or project.
		String text = ExplorationPath.markdown(item, report);
		JFileChooser chooser = new JFileChooser();
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Markdown", "md"));
		chooser.setSelectedFile(new java.io.File("investigation-" + item.finding().getId() + ".md"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		java.nio.file.Path path = chooser.getSelectedFile().toPath();
		if (java.nio.file.Files.exists(path) && JOptionPane.showConfirmDialog(this, AiText.str("analysis.overwrite"),
				AiText.str("explore.export"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			java.nio.file.Files.writeString(path, text, java.nio.charset.StandardCharsets.UTF_8);
			shown.setText(AiText.str("analysis.exported") + path);
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this, e.getMessage(), AiText.str("failed"), JOptionPane.ERROR_MESSAGE);
		}
	}

	private void editReview() {
		AssessmentItem item = selected();
		if (item == null || busy || report == null || !report.isComplete()) {
			return;
		}
		AnalysisReport original = report;
		AssessmentReview review = report.review(item.key());
		JPanel form = WorkbenchUi.transparent();
		JTextArea instructions = WorkbenchUi.body(AiText.str("assessment.review_hint"));
		instructions.setRows(3);
		form.add(instructions, BorderLayout.NORTH);
		JTextArea note = new JTextArea(review.getNote(), 9, 50);
		note.setLineWrap(true);
		note.setWrapStyleWord(true);
		note.getAccessibleContext().setAccessibleName(AiText.str("assessment.note"));
		form.add(new JScrollPane(note));
		JComboBox<AssessmentReview.Status> status = new JComboBox<>(AssessmentReview.Status.values());
		status.setSelectedItem(review.getStatus());
		JComboBox<AssessmentReview.Severity> severity = new JComboBox<>(AssessmentReview.Severity.values());
		severity.setSelectedItem(review.getSeverity());
		JPanel choices = flow();
		choices.add(new JLabel(AiText.str("assessment.state")));
		choices.add(status);
		choices.add(new JLabel(AiText.str("assessment.severity")));
		choices.add(severity);
		form.add(choices, BorderLayout.SOUTH);
		Object[] options = { AiText.str("assessment.save"), AiText.str("assessment.cancel") };
		while (JOptionPane.showOptionDialog(this, form, AiText.str("assessment.record") + " · " + item.finding().getId(),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]) == 0) {
			if (report != original || busy) {
				return;
			}
			try {
				report.review(item, new AssessmentReview((AssessmentReview.Status) status.getSelectedItem(),
						(AssessmentReview.Severity) severity.getSelectedItem(), note.getText()));
				save.run();
				setReport(report);
				return;
			} catch (IllegalArgumentException e) {
				JOptionPane.showMessageDialog(this, e.getMessage(), AiText.str("assessment.record"), JOptionPane.WARNING_MESSAGE);
			}
		}
	}

	private static JPanel flow() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
		panel.setOpaque(false);
		return panel;
	}

	private final class QueueModel extends AbstractTableModel {
		private final String[] columns = { "priority", "lead", "evidence", "state" };
		private List<AssessmentItem> rows = new ArrayList<>();

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return columns.length;
		}

		@Override
		public String getColumnName(int column) {
			if (!isReviewMode() && (column == 0 || column == 3)) {
				return AiText.str(column == 0 ? "explore.direction" : "explore.target");
			}
			return AiText.str("assessment." + columns[column]);
		}

		@Override
		public Class<?> getColumnClass(int column) {
			if (!isReviewMode()) {
				return column == 0 ? ExplorationPath.Direction.class : String.class;
			}
			return column == 0 ? AssessmentItem.Priority.class : column == 3 ? AssessmentReview.Status.class : String.class;
		}

		@Override
		public Object getValueAt(int row, int column) {
			AssessmentItem item = rows.get(row);
			switch (column) {
				case 0:
					return isReviewMode() ? item.priority() : ExplorationPath.direction(item);
				case 1:
					return item.title();
				case 2:
					return item.evidenceType();
				default:
					return isReviewMode() ? report.review(item.key()).getStatus() : ExplorationPath.next(item);
			}
		}
	}
}
