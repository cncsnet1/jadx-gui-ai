package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.utils.ui.WorkbenchUi;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;

/** Searchable local API inventory; no request execution is performed by this view. */
public final class ApiCatalogPanel extends JPanel {
	private final JTextField search = new JTextField();
	private final JComboBox<String> kind = new JComboBox<>();
	private final JLabel summary = WorkbenchUi.mutedLabel("");
	private final JTextArea detail = new JTextArea();
	private final ApiTableModel model = new ApiTableModel();
	private final JTable table = new JTable(model);
	private final JButton source = new JButton(AiText.str("analysis.source"));
	private final JButton trace = new JButton(AiText.str("api.trace"));
	private final JButton export = new JButton(AiText.str("api.export"));
	private final JButton relations = new JButton(AiText.str("api.relation.inspect"));
	private final JComboBox<String> evidenceSelector = new JComboBox<>();
	private final JTextArea evidenceText = new JTextArea();
	private final javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
	private final CallPathPanel callPath;
	private List<AiEvidence> selectedEvidence = List.of();
	private AnalysisReport report;
	private List<ApiEndpoint> endpoints = List.of();
	private boolean busy;
	private boolean complete;
	private boolean refreshing;

	public ApiCatalogPanel(Consumer<AiEvidence> open, Consumer<ApiEndpoint> investigate) {
		this(open, investigate, endpoint -> {
		});
	}

	public ApiCatalogPanel(Consumer<AiEvidence> open, Consumer<ApiEndpoint> investigate, Consumer<ApiEndpoint> inspect) {
		super(new BorderLayout(0, 8));
		setOpaque(false);
		callPath = new CallPathPanel(open);
		JPanel top = WorkbenchUi.transparent();
		JPanel controls = WorkbenchUi.transparent();
		kind.addItem(AiText.str("api.all"));
		for (ApiEndpoint.Origin origin : ApiEndpoint.Origin.values()) {
			kind.addItem(origin.label());
		}
		kind.getAccessibleContext().setAccessibleName(AiText.str("api.origin"));
		kind.addActionListener(e -> refresh());
		controls.add(kind, BorderLayout.WEST);
		search.putClientProperty("JTextField.placeholderText", AiText.str("api.search"));
		search.getAccessibleContext().setAccessibleName(AiText.str("api.search"));
		search.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refresh();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				refresh();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				refresh();
			}
		});
		controls.add(search);
		export.addActionListener(e -> export());
		controls.add(export, BorderLayout.EAST);
		top.add(controls, BorderLayout.NORTH);
		top.add(summary, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setAutoCreateRowSorter(true);
		table.setRowHeight(34);
		table.setShowGrid(false);
		table.setFillsViewportHeight(true);
		table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
			@Override
			public java.awt.Component getTableCellRendererComponent(JTable grid, Object value, boolean selected,
					boolean focused, int row, int column) {
				super.getTableCellRendererComponent(grid, value, selected, focused, row, column);
				putClientProperty("html.disable", true);
				setToolTipText(String.valueOf(value));
				setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
				return this;
			}
		});
		table.getAccessibleContext().setAccessibleName(AiText.str("api.title"));
		table.getColumnModel().getColumn(0).setPreferredWidth(115);
		table.getColumnModel().getColumn(1).setPreferredWidth(60);
		table.getColumnModel().getColumn(1).setMinWidth(70);
		table.getColumnModel().getColumn(2).setPreferredWidth(320);
		table.getColumnModel().getColumn(3).setPreferredWidth(180);
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && !refreshing) {
				selection();
			}
		});
		detail.setEditable(false);
		detail.setLineWrap(true);
		detail.setWrapStyleWord(true);
		detail.setFont(UIManager.getFont("Label.font"));
		detail.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		JPanel details = WorkbenchUi.transparent();
		tabs.addTab(AiText.str("api.path.title"), callPath);
		tabs.addTab(AiText.str("api.relation.title"), new JScrollPane(detail));
		JPanel evidencePanel = WorkbenchUi.transparent();
		evidenceSelector.getAccessibleContext().setAccessibleName(AiText.str("api.relation.evidence"));
		evidenceSelector.addActionListener(e -> {
			int index = evidenceSelector.getSelectedIndex();
			evidenceText.setText(index < 0 || index >= selectedEvidence.size() ? "" : selectedEvidence.get(index).getCode());
			evidenceText.setCaretPosition(0);
		});
		evidenceText.setEditable(false);
		evidenceText.setLineWrap(true);
		evidenceText.setWrapStyleWord(true);
		evidenceText.setFont(UIManager.getFont("Label.font"));
		evidenceText.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		evidencePanel.add(evidenceSelector, BorderLayout.NORTH);
		evidencePanel.add(new JScrollPane(evidenceText));
		tabs.addTab(AiText.str("api.relation.evidence"), evidencePanel);
		tabs.addChangeListener(e -> source.setVisible(tabs.getSelectedIndex() != 0));
		source.setVisible(false);
		details.add(tabs);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), details);
		split.setBorder(null);
		split.setResizeWeight(0.4);
		split.setDividerLocation(430);
		split.getLeftComponent().setMinimumSize(new Dimension(300, 90));
		split.getRightComponent().setMinimumSize(new Dimension(260, 100));
		add(split);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 2));
		actions.setOpaque(false);
		WorkbenchUi.primary(relations);
		trace.addActionListener(e -> {
			if (selected() != null) {
				investigate.accept(selected());
			}
		});
		source.addActionListener(e -> {
			int index = evidenceSelector.getSelectedIndex();
			if (index >= 0 && index < selectedEvidence.size()) {
				open.accept(selectedEvidence.get(index));
			}
		});
		relations.addActionListener(e -> {
			if (selected() != null) {
				tabs.setSelectedIndex(0);
				inspect.accept(selected());
			}
		});
		actions.add(relations);
		actions.add(trace);
		actions.add(source);
		add(actions, BorderLayout.SOUTH);
		refresh();
	}

	public void setReport(AnalysisReport report) {
		this.report = report;
		complete = report != null && report.isComplete();
		endpoints = report == null ? List.of() : report.getApis();
		if (report == null) {
			kind.setSelectedIndex(0);
			search.setText("");
			table.getRowSorter().setSortKeys(null);
		}
		refresh();
	}

	public void setBusy(boolean value) {
		busy = value;
		trace.setEnabled(!busy && complete && selected() != null);
		relations.setEnabled(!busy && complete && selected() != null);
		export.setEnabled(!busy && !endpoints.isEmpty());
	}

	public void selectEndpoint(String id) {
		kind.setSelectedIndex(0);
		search.setText("");
		for (int i = 0; i < model.rows.size(); i++) {
			if (model.rows.get(i).getId().equals(id)) {
				int row = table.convertRowIndexToView(i);
				table.setRowSelectionInterval(row, row);
				table.scrollRectToVisible(table.getCellRect(row, 0, true));
				return;
			}
		}
	}

	private ApiEndpoint selected() {
		int row = table.getSelectedRow();
		return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row));
	}

	private void refresh() {
		ApiEndpoint previous = selected();
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		int origin = kind.getSelectedIndex() - 1;
		List<ApiEndpoint> rows = endpoints.stream()
				.filter(e -> origin < 0 || e.getOrigin().ordinal() == origin)
				.filter(e -> query.isEmpty() || (e.getMethod() + ' ' + e.getHost() + ' ' + e.getAddress() + ' ' + e.parameterSummary() + ' '
						+ e.getSources().stream().map(AiEvidence::getTitle).collect(Collectors.joining(" "))).toLowerCase(Locale.ROOT)
								.contains(query))
				.sorted(java.util.Comparator.comparing(ApiEndpoint::getOrigin))
				.collect(Collectors.toList());
		refreshing = true;
		model.rows = rows;
		model.fireTableDataChanged();
		if (!rows.isEmpty()) {
			int index = 0;
			if (previous != null) {
				for (int i = 0; i < rows.size(); i++) {
					if (rows.get(i).getId().equals(previous.getId())) {
						index = i;
						break;
					}
				}
			}
			int view = table.convertRowIndexToView(index);
			table.setRowSelectionInterval(view, view);
		}
		refreshing = false;
		long declared = endpoints.stream().filter(e -> e.getOrigin() == ApiEndpoint.Origin.DECLARATION).count();
		summary.setText(String.format(AiText.str("api.summary"), rows.size(), endpoints.size(), declared));
		selection();
	}

	private void selection() {
		ApiEndpoint selected = selected();
		ApiDossier dossier = report == null || selected == null ? null : report.getApiDossier(selected.getId());
		callPath.setPath(dossier == null ? null : dossier.getCallPath());
		detail.setText(selected == null
				? AiText.str("api.empty")
				: selected.detail() + "\n\n"
						+ (dossier == null ? AiText.str("api.relation.select") : dossier.describe()));
		selectedEvidence = new java.util.ArrayList<>();
		if (selected != null) {
			selectedEvidence.addAll(selected.getSources());
		}
		if (dossier != null) {
			selectedEvidence.addAll(dossier.getEvidence());
		}
		evidenceSelector.removeAllItems();
		selectedEvidence.forEach(e -> evidenceSelector.addItem(e.getTitle()));
		detail.setCaretPosition(0);
		source.setEnabled(selected != null);
		setBusy(busy);
	}

	public static String json(List<ApiEndpoint> endpoints) {
		return defaultGsonBuilder().create().toJson(Map.of("schemaVersion", 1, "scope", "static observations; not runtime verified",
				"redacted", true, "endpoints", endpoints));
	}

	private void export() {
		List<ApiDossier> dossiers = report == null
				? List.of()
				: endpoints.stream().map(e -> report.getApiDossier(e.getId()))
						.filter(java.util.Objects::nonNull).collect(Collectors.toList());
		String data = defaultGsonBuilder().create().toJson(Map.of("schemaVersion", 3, "scope", "static observations; not runtime verified",
				"redacted", true, "endpoints", endpoints, "clientRelations", dossiers));
		JFileChooser chooser = new JFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
		chooser.setSelectedFile(new java.io.File("api-inventory.json"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		java.nio.file.Path path = chooser.getSelectedFile().toPath();
		if (Files.exists(path) && JOptionPane.showConfirmDialog(this, AiText.str("analysis.overwrite"), AiText.str("api.export"),
				JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			Files.writeString(path, data, StandardCharsets.UTF_8);
			summary.setText(AiText.str("analysis.exported") + path);
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this, e.getMessage(), AiText.str("failed"), JOptionPane.ERROR_MESSAGE);
		}
	}

	private static final class ApiTableModel extends AbstractTableModel {
		private List<ApiEndpoint> rows = List.of();
		private final String[] columns = { "api.origin", "api.method", "api.address", "api.host" };

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
			return AiText.str(columns[column]);
		}

		@Override
		public Object getValueAt(int row, int column) {
			ApiEndpoint endpoint = rows.get(row);
			switch (column) {
				case 0:
					return endpoint.getOrigin().label();
				case 1:
					return endpoint.getMethod().isEmpty() ? "—" : endpoint.getMethod();
				case 2:
					return endpoint.getAddress().isEmpty() ? AiText.str("api.runtime_url") : endpoint.getAddress();
				case 3:
					return endpoint.getHost().isEmpty() ? AiText.str("api.base_unknown") : endpoint.getHost();
				default:
					return "";
			}
		}
	}
}
