package jadx.gui.burp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiTaskRunner;
import jadx.gui.ai.analysis.AnalysisReport;
import jadx.gui.ai.analysis.ApiDossier;
import jadx.gui.ai.analysis.ApiEndpoint;
import jadx.gui.ai.analysis.ApiTrafficEvidence;
import jadx.gui.ai.analysis.ApiTrafficMatcher;
import jadx.gui.mcp.McpClient;
import jadx.gui.mcp.McpConnection;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.ui.WorkbenchUi;

/** User-driven Burp workflow. All network I/O is off the EDT; raw messages are session-only. */
public final class BurpTrafficPanel extends JPanel implements AutoCloseable {
	private static final class Choice {
		private final String id;
		private final String name;

		Choice(String id, String name) {
			this.id = id;
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private static final class Row {
		private final BurpExchange exchange;
		private final ApiTrafficMatcher.Match match;

		Row(BurpExchange exchange, ApiTrafficMatcher.Match match) {
			this.exchange = exchange;
			this.match = match;
		}
	}

	private final MainWindow window;
	private final Runnable changed;
	private final Consumer<AiEvidence> open;
	private final AiTaskRunner runner = new AiTaskRunner();
	private final JComboBox<Choice> connections = new JComboBox<>();
	private final JTextField origin = new JTextField();
	private final JTextArea status = WorkbenchUi.body(BurpText.str("intro"));
	private final JTextArea detail = WorkbenchUi.body(BurpText.str("empty"));
	private final JTextArea saved = WorkbenchUi.body("");
	private final TrafficModel model = new TrafficModel();
	private final JTable table = new JTable(model);
	private final JButton connect = button("connect", this::connect);
	private final JButton fetch = button("read", () -> read(false));
	private final JButton more = button("more", () -> read(true));
	private final JMenuItem cancel = menuItem("cancel", this::cancel);
	private final JMenuItem raw = menuItem("raw", this::raw);
	private final JButton retain = button("retain", this::retain);
	private final JButton stage = button("stage", this::stage);
	private final JButton source = button("source", this::source);
	private final JButton clearSaved = button("clear_saved", this::clearSaved);
	private final JPanel connectionHelp = flow();
	private final List<BurpExchange> session = new ArrayList<>();
	private AnalysisReport report;
	private ApiEndpoint endpoint;
	private ApiDossier dossier;
	private McpClient sessionClient;
	private URI sessionOrigin;
	private String suggestedOrigin = "";
	private int offset;
	private int skipped;
	private boolean end;
	private boolean busy;
	private boolean parentBusy;
	private boolean refreshingConnections;
	private long revision;

	public BurpTrafficPanel(MainWindow window, Consumer<AiEvidence> open, Runnable changed) {
		super(new BorderLayout(8, 8));
		this.window = window;
		this.open = open;
		this.changed = changed;
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		JPanel top = WorkbenchUi.transparent();
		JPanel toolbar = new JPanel(new java.awt.GridBagLayout());
		toolbar.setOpaque(false);
		DefaultListCellRenderer renderer = new DefaultListCellRenderer();
		renderer.putClientProperty("html.disable", true);
		connections.setRenderer(renderer);
		connections.setPrototypeDisplayValue(new Choice("", "Burp · localhost"));
		connections.setMinimumSize(new Dimension(100, connections.getPreferredSize().height));
		connections.getAccessibleContext().setAccessibleName(BurpText.str("connection"));
		connections.addActionListener(e -> {
			if (!refreshingConnections) {
				clearSession();
			}
		});
		connections.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override
			public void focusGained(java.awt.event.FocusEvent e) {
				refreshConnections();
			}
		});
		connections.addPopupMenuListener(new PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
				refreshConnections();
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e) {
			}
		});
		origin.putClientProperty("JTextField.placeholderText", "https://api.example.com");
		origin.getAccessibleContext().setAccessibleName(BurpText.str("origin"));
		origin.setToolTipText(BurpText.str("origin_hint"));
		origin.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				clearSession();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				clearSession();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				clearSession();
			}
		});
		origin.setMinimumSize(new Dimension(100, origin.getPreferredSize().height));
		java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
		constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
		constraints.gridy = 0;
		constraints.insets = new java.awt.Insets(0, 0, 0, 6);
		constraints.weightx = 0.25;
		toolbar.add(connections, constraints);
		constraints.weightx = 0;
		toolbar.add(connect, constraints);
		constraints.weightx = 0.75;
		toolbar.add(origin, constraints);
		constraints.weightx = 0;
		constraints.insets = new java.awt.Insets(0, 0, 0, 0);
		toolbar.add(fetch, constraints);
		WorkbenchUi.primary(fetch);
		top.add(toolbar);
		status.setRows(2);
		JScrollPane statusScroll = new JScrollPane(status);
		statusScroll.setBorder(null);
		JPanel notices = WorkbenchUi.transparent();
		notices.add(statusScroll);
		connectionHelp.add(button("install_help", BurpSetup::openInstallHelp));
		connectionHelp.add(button("connection_settings", this::configureConnection));
		connectionHelp.setVisible(false);
		notices.add(connectionHelp, BorderLayout.SOUTH);
		top.add(notices, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setRowHeight(30);
		table.setShowGrid(false);
		table.setFillsViewportHeight(true);
		table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
			@Override
			public java.awt.Component getTableCellRendererComponent(JTable grid, Object value, boolean selected,
					boolean focused, int row, int column) {
				super.getTableCellRendererComponent(grid, value, selected, focused, row, column);
				putClientProperty("html.disable", true);
				return this;
			}
		});
		table.getColumnModel().getColumn(1).setPreferredWidth(240);
		table.getSelectionModel().addListSelectionListener(e -> selection());
		table.getAccessibleContext().setAccessibleName(BurpText.str("title"));
		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setMinimumSize(new Dimension(100, 90));
		JScrollPane details = new JScrollPane(detail);
		details.setMinimumSize(new Dimension(100, 60));
		JTabbedPane views = new JTabbedPane();
		views.addTab(BurpText.str("session"), tableScroll);
		views.addTab(BurpText.str("match_details"), details);
		JPanel savedPanel = WorkbenchUi.transparent();
		savedPanel.add(new JScrollPane(saved));
		savedPanel.add(clearSaved, BorderLayout.SOUTH);
		views.addTab(BurpText.str("saved_evidence"), savedPanel);
		add(views);
		JPanel actions = flow();
		actions.add(source);
		actions.add(retain);
		actions.add(stage);
		actions.add(more);
		JPopupMenu menu = new JPopupMenu();
		menu.add(raw);
		menu.add(cancel);
		menu.addSeparator();
		menu.add(menuItem("connection_settings", this::configureConnection));
		menu.add(menuItem("install_help", BurpSetup::openInstallHelp));
		menu.add(menuItem("advanced", window::showMcpDialog));
		JButton options = new JButton(BurpText.str("options"));
		options.addActionListener(e -> menu.show(options, 0, options.getHeight()));
		actions.add(options);
		add(actions, BorderLayout.SOUTH);
		refreshConnections();
		controls();
	}

	public void setContext(AnalysisReport report, ApiEndpoint endpoint, ApiDossier dossier) {
		if (this.report != report || this.endpoint != endpoint) {
			cancel();
		}
		if (report == null || this.report == null || !Objects.equals(this.report.getFingerprint(), report.getFingerprint())) {
			clearSession();
			origin.setText("");
		}
		this.report = report;
		this.endpoint = endpoint;
		this.dossier = dossier;
		refreshConnections();
		boolean useSuggestion = origin.getText().isBlank() || origin.getText().equals(suggestedOrigin);
		String suggestion = "";
		if (endpoint != null) {
			List<String> addresses = new ArrayList<>();
			addresses.add(endpoint.getAddress());
			if (dossier != null) {
				dossier.getBindings().forEach(b -> addresses.add(b.getBaseUrl()));
			}
			for (String address : addresses) {
				try {
					URI uri = URI.create(address.replace("{", "%7B").replace("}", "%7D"));
					if (uri.getHost() != null && uri.getUserInfo() == null && List.of("http", "https").contains(uri.getScheme())) {
						suggestion = uri.getScheme() + "://" + uri.getRawAuthority();
						break;
					}
				} catch (IllegalArgumentException ignored) {
					// The user can enter an origin when static reconstruction is unavailable.
				}
			}
		}
		suggestedOrigin = suggestion;
		if (useSuggestion && !origin.getText().equals(suggestion)) {
			origin.setText(suggestion);
		}
		refreshRows();
		refreshSaved();
		controls();
	}

	public void setParentBusy(boolean value) {
		if (value && !parentBusy) {
			cancel();
		}
		parentBusy = value;
		controls();
	}

	private void refreshConnections() {
		Choice selected = (Choice) connections.getSelectedItem();
		refreshingConnections = true;
		connections.removeAllItems();
		for (McpConnection config : window.getSettings().getMcpConnections()) {
			McpClient client = window.getMcpManager().client(config.id);
			if ("burp-readonly".equals(config.toolProfile) || (client != null && BurpBridge.supports(client))) {
				Choice choice =
						new Choice(config.id, config.name + (client == null || client.isClosed() ? " · " + BurpText.str("offline") : ""));
				connections.addItem(choice);
				if (selected != null && choice.id.equals(selected.id)) {
					connections.setSelectedItem(choice);
				}
			}
		}
		refreshingConnections = false;
		controls();
	}

	private McpClient client() {
		Choice choice = (Choice) connections.getSelectedItem();
		return choice == null ? null : window.getMcpManager().client(choice.id);
	}

	private void connect() {
		if (busy || parentBusy) {
			return;
		}
		refreshConnections();
		Choice selected = (Choice) connections.getSelectedItem();
		McpConnection config = selected == null
				? BurpSetup.directConfiguration(BurpSetup.DEFAULT_URL)
				: window.getSettings().getMcpConnections().stream().filter(c -> c.id.equals(selected.id)).findFirst().orElse(null);
		if (config == null) {
			return;
		}
		connect(config);
	}

	private void configureConnection() {
		if (!busy && !parentBusy) {
			Choice choice = (Choice) connections.getSelectedItem();
			McpConnection existing = choice == null
					? null
					: window.getSettings().getMcpConnections().stream()
							.filter(c -> c.id.equals(choice.id) && "sse".equals(c.transport)).findFirst().orElse(null);
			McpConnection config = BurpSetup.choose(this, existing);
			if (config != null) {
				connect(config);
			}
		}
	}

	private void connect(McpConnection config) {
		if (busy || parentBusy) {
			return;
		}
		clearSession();
		window.getMcpManager().disconnect(config.id);
		long epoch = ++revision;
		busy = true;
		controls();
		setStatus(BurpText.str("connecting"));
		runner.submit(token -> {
			McpClient connected = McpClient.connect(config, token);
			if (!BurpBridge.supports(connected)) {
				connected.close();
				throw new java.io.IOException(BurpText.str("incompatible"));
			}
			SwingUtilities.invokeLater(() -> {
				if (epoch != revision || token.isCancelled()) {
					connected.close();
					return;
				}
				window.getMcpManager().add(connected);
				List<McpConnection> configs = new ArrayList<>(window.getSettings().getMcpConnections());
				configs.removeIf(c -> c.id.equals(config.id));
				configs.add(config);
				window.getSettings().setMcpConnections(configs);
				window.getSettings().sync();
				busy = false;
				refreshConnections();
				refreshingConnections = true;
				for (int i = 0; i < connections.getItemCount(); i++) {
					if (config.id.equals(connections.getItemAt(i).id)) {
						connections.setSelectedIndex(i);
						break;
					}
				}
				refreshingConnections = false;
				controls();
				setStatus(BurpText.str("connected"));
			});
			return true;
		}, ignored -> {
		}, error -> SwingUtilities.invokeLater(() -> {
			if (epoch == revision) {
				busy = false;
				controls();
				setStatus(BurpText.str("incompatible").equals(error.getMessage())
						? BurpText.str("incompatible")
						: BurpText.str("connect_failed"));
				connectionHelp.setVisible(true);
				revalidate();
			}
		}));
	}

	private void read(boolean append) {
		McpClient client = client();
		if (busy || parentBusy || client == null || client.isClosed() || report == null || !report.isComplete()) {
			return;
		}
		try {
			URI scope = BurpBridge.origin(origin.getText());
			if (!append) {
				clearSession();
				sessionClient = client;
				sessionOrigin = scope;
			}
			if (sessionClient != client || !scope.equals(sessionOrigin) || offset >= 500 || end) {
				return;
			}
			long epoch = ++revision;
			int start = offset;
			busy = true;
			controls();
			setStatus(BurpText.str("reading"));
			runner.submit(token -> BurpBridge.read(client, scope, start, token), page -> SwingUtilities.invokeLater(() -> {
				if (epoch != revision) {
					return;
				}
				if (client() != client || client.isClosed()) {
					clearSession();
					setStatus(BurpText.str("operation_failed"));
					return;
				}
				session.addAll(page.exchanges());
				offset = page.nextOffset();
				skipped += page.skipped();
				end = page.end();
				busy = false;
				refreshRows();
				setStatus(String.format(BurpText.str("read_summary"), offset, session.size(), skipped)
						+ "\n" + BurpText.str(end ? "end" : offset >= 500 ? "limit" : "more_hint"));
			}), error -> failure(epoch, error));
		} catch (IllegalArgumentException e) {
			setStatus(BurpText.str("invalid_origin"));
		}
	}

	private void refreshRows() {
		model.rows = session.stream().map(e -> new Row(e, ApiTrafficMatcher.match(endpoint, dossier, e))).collect(Collectors.toList());
		model.fireTableDataChanged();
		if (!model.rows.isEmpty()) {
			table.setRowSelectionInterval(0, 0);
		}
		selection();
	}

	private Row selected() {
		int index = table.getSelectedRow();
		return index < 0 || index >= model.rows.size() ? null : model.rows.get(index);
	}

	private void selection() {
		Row row = selected();
		if (row == null) {
			detail.setText(BurpText.str("empty"));
		} else {
			BurpExchange e = row.exchange;
			detail.setText((row.match == null ? BurpText.str("unmatched") : row.match.level().label() + " · " + row.match.reason())
					+ "\n" + BurpText.str("header_names") + ": " + String.join(", ", e.headerNames())
					+ "\n" + BurpText.str("query_names") + ": " + String.join(", ", e.queryNames())
					+ "\n" + (e.truncated() ? BurpText.str("truncated") + "\n" : "") + BurpText.str("evidence_boundary"));
		}
		detail.setCaretPosition(0);
		controls();
	}

	private void refreshSaved() {
		saved.setText(report == null || endpoint == null
				? BurpText.str("no_saved")
				: report.getTrafficEvidence().stream().filter(e -> e.belongsTo(endpoint)).map(ApiTrafficEvidence::describe)
						.collect(Collectors.joining("\n\n────────────────\n\n")));
		if (saved.getText().isBlank()) {
			saved.setText(BurpText.str("no_saved"));
		}
		saved.setCaretPosition(0);
	}

	private void retain() {
		Row row = selected();
		if (row == null || row.match == null || report == null || busy || parentBusy) {
			return;
		}
		try {
			report.putTrafficEvidence(endpoint, new ApiTrafficEvidence(endpoint, row.exchange, sessionOrigin, row.match));
			changed.run();
			refreshSaved();
			setStatus(BurpText.str("retained"));
		} catch (IllegalStateException | IllegalArgumentException e) {
			setStatus(e.getMessage());
		}
	}

	private void clearSaved() {
		if (report != null && endpoint != null && !busy && !parentBusy) {
			report.clearTrafficEvidence(endpoint);
			changed.run();
			refreshSaved();
			controls();
		}
	}

	private void source() {
		Row row = selected();
		if (row != null && row.match != null && endpoint != null && !endpoint.getSources().isEmpty()) {
			open.accept(endpoint.getSources().get(0));
		}
	}

	private void raw() {
		Row row = selected();
		if (row == null) {
			return;
		}
		JTabbedPane tabs = new JTabbedPane();
		for (String name : List.of("request", "response")) {
			JTextArea text = new JTextArea(name.equals("request") ? row.exchange.rawRequest() : row.exchange.rawResponse());
			text.setEditable(false);
			text.setCaretPosition(0);
			tabs.addTab(BurpText.str(name), new JScrollPane(text));
		}
		tabs.setPreferredSize(new Dimension(760, 480));
		JOptionPane.showMessageDialog(this, tabs, BurpText.str("raw_title"), JOptionPane.PLAIN_MESSAGE);
	}

	private void stage() {
		Row row = selected();
		McpClient client = client();
		if (row == null || client == null || client != sessionClient || client.isClosed() || busy || parentBusy
				|| !row.exchange.canStage() || sessionOrigin == null) {
			return;
		}
		long beforeDialog = revision;
		JTextArea preview = new JTextArea(BurpText.str("stage_confirm") + "\n\n" + sessionOrigin + "\n\n" + row.exchange.rawRequest());
		preview.setEditable(false);
		preview.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(preview);
		scroll.setPreferredSize(new Dimension(700, 390));
		if (JOptionPane.showConfirmDialog(this, scroll, BurpText.str("stage"), JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION || beforeDialog != revision || client() != client) {
			return;
		}
		URI scope = sessionOrigin;
		long epoch = ++revision;
		busy = true;
		controls();
		setStatus(BurpText.str("staging"));
		runner.submit(token -> {
			BurpBridge.stage(client, row.exchange, scope, token);
			return true;
		}, result -> SwingUtilities.invokeLater(() -> {
			if (epoch == revision) {
				busy = false;
				controls();
				setStatus(BurpText.str("staged"));
			}
		}), error -> failure(epoch, error));
	}

	private void setStatus(String text) {
		status.setText(text);
		status.setCaretPosition(0);
	}

	private void failure(long epoch, Exception error) {
		SwingUtilities.invokeLater(() -> {
			if (epoch == revision) {
				busy = false;
				controls();
				setStatus(error instanceof java.io.IOException ? error.getMessage() : BurpText.str("operation_failed"));
			}
		});
	}

	private void cancel() {
		revision++;
		runner.cancel();
		if (busy) {
			setStatus(BurpText.str("cancelled"));
		}
		busy = false;
		controls();
	}

	private void clearSession() {
		cancel();
		connectionHelp.setVisible(false);
		session.clear();
		sessionClient = null;
		sessionOrigin = null;
		offset = 0;
		skipped = 0;
		end = false;
		refreshRows();
		setStatus(BurpText.str("intro"));
	}

	private void controls() {
		boolean idle = !busy && !parentBusy;
		McpClient client = client();
		boolean connected = client != null && !client.isClosed();
		boolean ready = idle && connected && report != null && report.isComplete() && endpoint != null;
		connections.setEnabled(idle);
		origin.setEnabled(idle);
		connect.setEnabled(idle);
		fetch.setEnabled(ready);
		more.setEnabled(ready && sessionClient == client && !end && offset > 0 && offset < 500);
		cancel.setEnabled(busy);
		Row row = selected();
		raw.setEnabled(idle && row != null);
		retain.setEnabled(idle && row != null && row.match != null && report != null && report.isComplete());
		clearSaved.setEnabled(idle && report != null && report.isComplete() && endpoint != null
				&& report.getTrafficEvidence().stream().anyMatch(e -> e.belongsTo(endpoint)));
		source.setEnabled(idle && row != null && row.match != null);
		stage.setEnabled(ready && sessionClient == client && row != null && row.exchange.canStage());
		stage.setToolTipText(row != null && !row.exchange.canStage() ? BurpText.str("cannot_stage") : BurpText.str("stage_confirm"));
	}

	@Override
	public void close() {
		clearSession();
		runner.close();
	}

	private static JPanel flow() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 5, 0));
		panel.setOpaque(false);
		return panel;
	}

	private static JButton button(String key, Runnable action) {
		JButton button = new JButton(BurpText.str(key));
		button.addActionListener(e -> action.run());
		return button;
	}

	private static JMenuItem menuItem(String key, Runnable action) {
		JMenuItem item = new JMenuItem(BurpText.str(key));
		item.addActionListener(e -> action.run());
		return item;
	}

	private static final class TrafficModel extends AbstractTableModel {
		private List<Row> rows = List.of();
		private final String[] columns = { "method", "path", "status", "match" };

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
			return BurpText.str(columns[column]);
		}

		@Override
		public Object getValueAt(int row, int column) {
			Row value = rows.get(row);
			switch (column) {
				case 0:
					return value.exchange.method();
				case 1:
					return value.exchange.path();
				case 2:
					return value.exchange.status() == 0 ? "—" : value.exchange.status();
				case 3:
					return value.match == null ? "—" : value.match.level().label();
				default:
					return "";
			}
		}
	}
}
