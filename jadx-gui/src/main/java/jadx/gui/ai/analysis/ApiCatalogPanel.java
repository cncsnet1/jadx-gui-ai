package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.burp.BurpTrafficPanel;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.layout.WrapLayout;
import jadx.gui.utils.ui.WorkbenchUi;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;

/** Searchable local API inventory; no request execution is performed by this view. */
public final class ApiCatalogPanel extends JPanel {
	private final JTextField search = new JTextField();
	private final JComboBox<String> kind = new JComboBox<>();
	private final JCheckBox showBackground = new JCheckBox(WorkbenchText.str("triage.show_background"));
	private final JLabel summary = WorkbenchUi.mutedLabel("");
	private final JTextArea detail = new JTextArea();
	private final JTextArea crypto = new JTextArea();
	private final JTextArea outlet = new JTextArea();
	private final JTextArea routeStatus = WorkbenchUi.body(CommunicationText.str("select"));
	private final ApiTableModel model = new ApiTableModel();
	private final JTable table = new JTable(model);
	private final JButton source = new JButton(AiText.str("analysis.source"));
	private final JButton trace = new JButton(AiText.str("api.trace"));
	private final JButton export = new JButton(AiText.str("api.export"));
	private final JButton relations = new JButton(CommunicationText.str("inspect"));
	private final JButton evidence = new JButton(CommunicationText.str("evidence"));
	private final JComboBox<String> evidenceSelector = new JComboBox<>();
	private final JTextArea evidenceText = new JTextArea();
	private final JPanel stages = new JPanel(new CardLayout());
	private final JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEADING, 8, 2));
	private final JToggleButton[] stageButtons = new JToggleButton[5];
	private final CallPathPanel callPath;
	private final BurpTrafficPanel traffic;
	private List<AiEvidence> selectedEvidence = List.of();
	private List<AiEvidence> endpointEvidence = List.of();
	private List<AiEvidence> cryptoEvidence = List.of();
	private int stage = 1;
	private AnalysisReport report;
	private List<ApiEndpoint> endpoints = List.of();
	private final Map<String, EvidenceTriage.Decision> triage = new HashMap<>();
	private boolean busy;
	private boolean complete;
	private boolean refreshing;
	private int foldedCount;

	public ApiCatalogPanel(Consumer<AiEvidence> open, Consumer<ApiEndpoint> investigate) {
		this(open, investigate, endpoint -> {
		});
	}

	public ApiCatalogPanel(Consumer<AiEvidence> open, Consumer<ApiEndpoint> investigate, Consumer<ApiEndpoint> inspect) {
		this(open, investigate, inspect, null, () -> {
		});
	}

	public ApiCatalogPanel(Consumer<AiEvidence> open, Consumer<ApiEndpoint> investigate, Consumer<ApiEndpoint> inspect,
			MainWindow window, Runnable changed) {
		super(new BorderLayout(0, 8));
		setOpaque(false);
		callPath = new CallPathPanel(open);
		traffic = window == null ? null : new BurpTrafficPanel(window, open, changed);
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
		JPanel focus = new JPanel(new WrapLayout(FlowLayout.LEADING, 8, 2));
		focus.setOpaque(false);
		focus.add(WorkbenchUi.mutedLabel(WorkbenchText.str("triage.api_focus")));
		showBackground.setOpaque(false);
		showBackground.setToolTipText(WorkbenchText.str("triage.api_hint"));
		showBackground.getAccessibleContext().setAccessibleDescription(WorkbenchText.str("triage.api_hint"));
		showBackground.addActionListener(e -> refresh());
		focus.add(showBackground);
		top.add(focus, BorderLayout.CENTER);
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
		for (JTextArea text : List.of(detail, crypto, outlet, evidenceText)) {
			text.setEditable(false);
			text.setLineWrap(true);
			text.setWrapStyleWord(true);
			text.setFont(UIManager.getFont("Label.font"));
			text.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		}
		JPanel details = WorkbenchUi.transparent();
		JPanel route = WorkbenchUi.transparent();
		JPanel steps = new JPanel(new WrapLayout(FlowLayout.LEADING, 4, 4));
		steps.setOpaque(false);
		ButtonGroup group = new ButtonGroup();
		String[] keys = { "entry", "parameters", "crypto", "outlet", "traffic" };
		for (int i = 0; i < keys.length; i++) {
			final int index = i;
			JToggleButton button = new JToggleButton(CommunicationText.str(keys[i]));
			button.addActionListener(e -> showStage(index));
			group.add(button);
			steps.add(button);
			stageButtons[i] = button;
		}
		stageButtons[stage].setSelected(true);
		routeStatus.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		routeStatus.setRows(3);
		route.add(steps, BorderLayout.NORTH);
		route.add(routeStatus);
		details.add(route, BorderLayout.NORTH);
		stages.setOpaque(false);
		stages.add(callPath, "0");
		stages.add(new JScrollPane(detail), "1");
		stages.add(new JScrollPane(crypto), "2");
		stages.add(new JScrollPane(outlet), "3");
		evidenceSelector.getAccessibleContext().setAccessibleName(AiText.str("api.relation.evidence"));
		evidenceSelector.addActionListener(e -> {
			int index = evidenceSelector.getSelectedIndex();
			String text = index < 0 || index >= selectedEvidence.size() ? "" : selectedEvidence.get(index).getCode();
			evidenceText.setText(text.isEmpty() && selected() != null ? selected().detail() : text);
			evidenceText.setCaretPosition(0);
		});
		if (traffic != null) {
			stages.add(traffic, "4");
		} else {
			stages.add(new JScrollPane(WorkbenchUi.body(CommunicationText.str("traffic_unavailable"))), "4");
		}
		details.add(stages);
		JPanel evidenceActions = WorkbenchUi.transparent();
		evidenceActions.add(evidenceSelector);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 2));
		buttons.setOpaque(false);
		buttons.add(evidence);
		buttons.add(source);
		evidenceActions.add(buttons, BorderLayout.EAST);
		details.add(evidenceActions, BorderLayout.SOUTH);
		evidence.addActionListener(e -> {
			JScrollPane scroll = new JScrollPane(evidenceText);
			scroll.setPreferredSize(new Dimension(640, 360));
			JOptionPane.showMessageDialog(this, scroll, CommunicationText.str("evidence"), JOptionPane.PLAIN_MESSAGE);
		});
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), details);
		split.setBorder(null);
		split.setResizeWeight(0.4);
		split.setDividerLocation(430);
		split.getLeftComponent().setMinimumSize(new Dimension(300, 90));
		split.getRightComponent().setMinimumSize(new Dimension(260, 100));
		add(split);
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
				showStage(0);
				inspect.accept(selected());
			}
		});
		actions.add(relations);
		actions.add(trace);
		add(actions, BorderLayout.SOUTH);
		refresh();
	}

	public void setReport(AnalysisReport report) {
		this.report = report;
		complete = report != null && report.isComplete();
		endpoints = report == null ? List.of() : report.getApis();
		triage.clear();
		endpoints.forEach(endpoint -> triage.put(endpoint.getId(), EvidenceTriage.assess(report, endpoint.asFinding())));
		if (report == null) {
			showBackground.setSelected(false);
			kind.setSelectedIndex(0);
			search.setText("");
			table.getRowSorter().setSortKeys(null);
		}
		refresh();
	}

	public void setBusy(boolean value) {
		busy = value;
		if (traffic != null) {
			traffic.setParentBusy(value);
		}
		trace.setEnabled(!busy && complete && selected() != null);
		relations.setEnabled(!busy && complete && selected() != null);
		export.setEnabled(!busy && !endpoints.isEmpty());
	}

	public void selectEndpoint(String id) {
		EvidenceTriage.Decision decision = triage.get(id);
		if (decision != null && decision.getTier() == EvidenceTriage.Tier.BACKGROUND) {
			showBackground.setSelected(true);
		}
		kind.setSelectedIndex(0);
		search.setText("");
		refresh();
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
		List<ApiEndpoint> matching = endpoints.stream()
				.filter(e -> origin < 0 || e.getOrigin().ordinal() == origin)
				.filter(e -> query.isEmpty() || (e.getMethod() + ' ' + e.getHost() + ' ' + e.getAddress() + ' ' + e.parameterSummary() + ' '
						+ e.getSources().stream().map(AiEvidence::getTitle).collect(Collectors.joining(" "))).toLowerCase(Locale.ROOT)
								.contains(query))
				.collect(Collectors.toList());
		List<ApiEndpoint> rows = matching.stream()
				.filter(e -> showBackground.isSelected() || triage.get(e.getId()).getTier() != EvidenceTriage.Tier.BACKGROUND)
				.sorted(Comparator.<ApiEndpoint>comparingInt(e -> triage.get(e.getId()).getScore()).reversed())
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
		foldedCount = matching.size() - rows.size();
		summary.setText(String.format(WorkbenchText.str("triage.api_summary"), rows.size(), endpoints.size(), foldedCount));
		selection();
	}

	private void selection() {
		ApiEndpoint selected = selected();
		ApiDossier dossier = report == null || selected == null ? null : report.getApiDossier(selected.getId());
		if (traffic != null) {
			traffic.setContext(report, selected, dossier);
		}
		callPath.setPath(dossier == null ? null : dossier.getCallPath());
		String empty = foldedCount > 0 ? WorkbenchText.str("triage.api_folded_empty") : AiText.str("api.empty");
		detail.setText(selected == null
				? empty
				: TriageText.describe(triage.get(selected.getId())) + "\n\n" + selected.detail());
		outlet.setText(CommunicationEvidence.outlet(selected, dossier));
		cryptoEvidence = CommunicationEvidence.crypto(report, selected, dossier);
		crypto.setText(CommunicationEvidence.cryptoDescription(cryptoEvidence));
		routeStatus.setText(CommunicationText.str(selected == null ? "select" : dossier == null ? "not_traced" : "traced"));
		relations.setText(CommunicationText.str(dossier == null ? "inspect" : "cached"));
		endpointEvidence = new java.util.ArrayList<>();
		if (selected != null) {
			endpointEvidence.addAll(selected.getSources());
		}
		if (dossier != null) {
			endpointEvidence.addAll(dossier.getEvidence());
		}
		detail.setCaretPosition(0);
		outlet.setCaretPosition(0);
		crypto.setCaretPosition(0);
		showStage(stage);
		setBusy(busy);
	}

	private void showStage(int index) {
		stage = index;
		stageButtons[index].setSelected(true);
		((CardLayout) stages.getLayout()).show(stages, Integer.toString(index));
		actions.setVisible(index != 4);
		selectedEvidence = index == 2 ? cryptoEvidence : endpointEvidence;
		evidenceSelector.removeAllItems();
		selectedEvidence.forEach(e -> evidenceSelector.addItem(e.getTitle()));
		evidence.setEnabled(!selectedEvidence.isEmpty());
		source.setEnabled(!selectedEvidence.isEmpty());
		evidenceSelector.getParent().setVisible(index != 4);
	}

	public static String json(List<ApiEndpoint> endpoints) {
		return defaultGsonBuilder().create().toJson(Map.of("schemaVersion", 2, "scope", "static observations; not runtime verified",
				"redacted", false, "endpoints", endpoints));
	}

	String exportJson() {
		List<ApiDossier> dossiers = report == null
				? List.of()
				: endpoints.stream().map(e -> report.getApiDossier(e.getId()))
						.filter(java.util.Objects::nonNull).collect(Collectors.toList());
		return defaultGsonBuilder().create().toJson(Map.of("schemaVersion", 5,
				"scope", "static declarations and observed HTTP records; correlations do not prove code execution or vulnerabilities",
				"redacted", false, "endpoints", endpoints, "clientRelations", dossiers,
				"trafficEvidence", report == null ? List.of() : report.trafficEvidenceExport()));
	}

	private void export() {
		String data = exportJson();
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

	public void close() {
		if (traffic != null) {
			traffic.close();
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
