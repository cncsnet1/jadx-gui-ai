package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.gui.ai.AiTaskRunner;
import jadx.gui.mcp.McpClient;
import jadx.gui.mcp.McpConnection;
import jadx.gui.mcp.McpManager;
import jadx.gui.mcp.McpServer;
import jadx.gui.mcp.McpText;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.ui.WorkbenchUi;

public final class McpDialog extends JDialog {

	private static final long serialVersionUID = 1L;
	private final MainWindow owner;
	private final McpManager manager;
	private final AiTaskRunner runner = new AiTaskRunner();
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final DefaultListModel<McpConnection> connections = new DefaultListModel<>();
	private final JList<McpConnection> list = new JList<>(connections);
	private final JTextField name = new JTextField();
	private final JComboBox<String> transport = new JComboBox<>(new String[] { "stdio", "http" });
	private final JTextField command = new JTextField();
	private final JTextArea args = new JTextArea("[]", 4, 25);
	private final JTextArea env = new JTextArea("{}", 2, 25);
	private final JTextField url = new JTextField();
	private final JTextField bearer = new JTextField();
	private final JComboBox<String> tools = new JComboBox<>();
	private final JTextArea arguments = new JTextArea("{}", 3, 25);
	private final JTextArea output = new JTextArea();
	private final JTextArea status = note("idle");
	private final JTextArea localConfig = new JTextArea();
	private final JLabel localStatus = new JLabel();
	private final JSpinner port = new JSpinner(new SpinnerNumberModel(8650, 0, 65535, 1));
	private final List<JComponent> editing = new ArrayList<>();
	private final JTabbedPane clientTabs = new JTabbedPane();
	private final Timer serverStatus;
	private McpConnection current;
	private long revision;
	private boolean busy;

	public McpDialog(MainWindow owner) {
		super(owner, McpText.str("title"), false);
		this.owner = owner;
		this.manager = owner.getMcpManager();
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		JPanel content = WorkbenchUi.card(20);
		JPanel header = WorkbenchUi.transparent();
		header.add(WorkbenchUi.title(McpText.str("title"), 7), BorderLayout.NORTH);
		JTextArea subtitle = note("subtitle");
		subtitle.setRows(2);
		header.add(subtitle);
		content.add(header, BorderLayout.NORTH);
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab(McpText.str("server_tab"), serverPanel());
		tabs.addTab(McpText.str("client_tab"), clientPanel());
		content.add(tabs);
		JPanel footer = new JPanel(new BorderLayout(8, 0));
		status.setRows(2);
		footer.add(status);
		JPanel footerActions = new JPanel(new FlowLayout(FlowLayout.TRAILING));
		footerActions.add(button("cancel", () -> {
			revision++;
			runner.cancel();
			setBusy(false);
			status.setText(McpText.str("cancelled"));
		}));
		footerActions.add(button("close", this::dispose));
		footer.add(footerActions, BorderLayout.EAST);
		content.add(footer, BorderLayout.SOUTH);
		setContentPane(content);
		setMinimumSize(new Dimension(760, 600));
		setSize(1000, 800);
		setLocationRelativeTo(owner);
		owner.getSettings().getMcpConnections().forEach(connections::addElement);
		if (!connections.isEmpty()) {
			list.setSelectedIndex(0);
		} else {
			addConnection();
		}
		serverStatus = new Timer(800, event -> refreshServer());
		serverStatus.start();
		refreshServer();
	}

	private JPanel serverPanel() {
		JPanel panel = WorkbenchUi.card(16);
		panel.add(note("server_hint"), BorderLayout.NORTH);
		localConfig.setEditable(false);
		localConfig.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
		JPanel center = WorkbenchUi.transparent();
		center.add(localStatus, BorderLayout.NORTH);
		center.add(new JScrollPane(localConfig));
		center.add(note("config_hint"), BorderLayout.SOUTH);
		panel.add(center);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
		actions.add(new JLabel(McpText.str("port")));
		actions.add(port);
		actions.add(button("start", () -> {
			try {
				if (!manager.isProjectReady()) {
					throw new IllegalStateException(McpText.str("need_project"));
				}
				port.commitEdit();
				manager.startServer(owner.getWrapper().getDecompiler(), (Integer) port.getValue());
				refreshServer();
			} catch (Exception e) {
				status.setText(e.getMessage());
			}
		}));
		actions.add(button("stop", () -> {
			manager.stopServer();
			refreshServer();
		}));
		actions.add(button("copy", () -> {
			McpServer server = manager.server();
			if (server != null) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(server.configuration()), null);
			}
		}));
		panel.add(actions, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel clientPanel() {
		JPanel navigation = WorkbenchUi.card(8);
		DefaultListCellRenderer renderer = new DefaultListCellRenderer();
		renderer.putClientProperty("html.disable", true);
		list.setCellRenderer(renderer);
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		list.addListSelectionListener(event -> {
			if (!event.getValueIsAdjusting()) {
				select(list.getSelectedValue());
			}
		});
		navigation.add(new JScrollPane(list));
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
		JButton add = button("add", this::addConnection);
		JButton remove = button("remove", () -> {
			if (current != null) {
				manager.disconnect(current.id);
				connections.removeElement(current);
				persist();
				if (!connections.isEmpty()) {
					list.setSelectedIndex(0);
				}
			}
		});
		actions.add(add);
		actions.add(remove);
		editing.add(add);
		editing.add(remove);
		editing.add(list);
		navigation.add(actions, BorderLayout.SOUTH);
		for (JTextArea area : List.of(args, env, arguments)) {
			area.setLineWrap(true);
			area.setWrapStyleWord(true);
		}
		JPanel common = new JPanel(new GridBagLayout());
		field(common, 0, "name", name);
		field(common, 1, "transport", transport);
		JPanel stdio = new JPanel(new GridBagLayout());
		field(stdio, 0, "command", command);
		field(stdio, 1, "args", new JScrollPane(args));
		field(stdio, 2, "env", new JScrollPane(env));
		JPanel http = new JPanel(new GridBagLayout());
		field(http, 0, "url", url);
		field(http, 1, "bearer", bearer);
		JPanel cards = new JPanel(new CardLayout());
		cards.add(connectionCard(stdio, "stdio_hint"), "stdio");
		cards.add(connectionCard(http, "http_hint"), "http");
		transport.addActionListener(event -> ((CardLayout) cards.getLayout()).show(cards, (String) transport.getSelectedItem()));
		ScrollForm form = new ScrollForm();
		JPanel fields = new JPanel(new BorderLayout());
		fields.add(common, BorderLayout.NORTH);
		fields.add(cards);
		form.add(fields, BorderLayout.NORTH);
		JPanel connectionActions = new JPanel(new java.awt.GridLayout(1, 0, 6, 0));
		connectionActions.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		JButton save = button("save", () -> {
			if (save()) {
				status.setText(McpText.str("saved"));
			}
		});
		JButton connect = button("connect", this::connect);
		JButton disconnect = button("disconnect", () -> {
			if (current != null) {
				manager.disconnect(current.id);
				refreshTools();
				status.setText(McpText.str("disconnected"));
			}
		});
		connectionActions.add(save);
		connectionActions.add(connect);
		connectionActions.add(disconnect);
		editing.addAll(List.of(name, transport, command, args, env, url, bearer, save, connect, disconnect));
		JPanel settings = new JPanel(new BorderLayout());
		JScrollPane settingsScroll = new JScrollPane(form);
		settingsScroll.setBorder(BorderFactory.createEmptyBorder());
		settingsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		settings.add(settingsScroll);
		settings.add(connectionActions, BorderLayout.SOUTH);
		JPanel toolPanel = WorkbenchUi.card(10);
		JPanel toolHeader = new JPanel(new BorderLayout(8, 0));
		toolHeader.add(new JLabel(McpText.str("tools")), BorderLayout.WEST);
		DefaultListCellRenderer toolRenderer = new DefaultListCellRenderer();
		toolRenderer.putClientProperty("html.disable", true);
		tools.setRenderer(toolRenderer);
		toolHeader.add(tools);
		JButton invoke = button("invoke", this::invoke);
		toolHeader.add(invoke, BorderLayout.EAST);
		tools.addActionListener(event -> showSchema());
		editing.add(invoke);
		editing.add(tools);
		editing.add(arguments);
		toolPanel.add(toolHeader, BorderLayout.NORTH);
		arguments.setBorder(BorderFactory.createTitledBorder(McpText.str("arguments")));
		output.setEditable(false);
		output.setLineWrap(true);
		output.setWrapStyleWord(true);
		output.setBorder(BorderFactory.createTitledBorder(McpText.str("result")));
		JSplitPane results = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(arguments), new JScrollPane(output));
		results.setResizeWeight(0.3);
		results.setDividerLocation(130);
		toolPanel.add(results);
		clientTabs.addTab(McpText.str("connection_tab"), settings);
		clientTabs.addTab(McpText.str("test_tab"), toolPanel);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigation, clientTabs);
		split.setDividerLocation(210);
		JPanel panel = new JPanel(new BorderLayout());
		panel.add(split);
		return panel;
	}

	private void select(McpConnection connection) {
		current = connection;
		if (connection != null) {
			name.setText(connection.name);
			transport.setSelectedItem(connection.transport);
			command.setText(connection.command);
			args.setText(gson.toJson(connection.args));
			args.setCaretPosition(0);
			env.setText(gson.toJson(connection.environment));
			env.setCaretPosition(0);
			url.setText(connection.url);
			bearer.setText(connection.bearerEnvironment);
		}
		refreshTools();
	}

	private void addConnection() {
		McpConnection connection = new McpConnection();
		connection.name = "MCP " + (connections.size() + 1);
		connections.addElement(connection);
		list.setSelectedValue(connection, true);
	}

	private boolean save() {
		if (current == null) {
			return false;
		}
		try {
			McpConnection draft = current.copy();
			draft.name = name.getText().trim();
			draft.transport = (String) transport.getSelectedItem();
			draft.command = command.getText().trim();
			if ("stdio".equals(draft.transport)) {
				draft.args.clear();
				for (JsonElement arg : JsonParser.parseString(args.getText()).getAsJsonArray()) {
					if (!arg.isJsonPrimitive() || !arg.getAsJsonPrimitive().isString()) {
						throw new IllegalArgumentException("Arguments must be a JSON array of strings");
					}
					draft.args.add(arg.getAsString());
				}
				draft.environment.clear();
				JsonObject variables = JsonParser.parseString(env.getText()).getAsJsonObject();
				for (String key : variables.keySet()) {
					if (!variables.get(key).isJsonPrimitive() || !variables.get(key).getAsJsonPrimitive().isString()) {
						throw new IllegalArgumentException("Environment must map strings to strings");
					}
					draft.environment.put(key, variables.get(key).getAsString());
				}
			}
			draft.url = url.getText().trim();
			draft.bearerEnvironment = bearer.getText().trim();
			draft.validate();
			if (!gson.toJson(draft).equals(gson.toJson(current))) {
				manager.disconnect(current.id);
			}
			int index = connections.indexOf(current);
			current = draft;
			connections.set(index, draft);
			persist();
			refreshTools();
			return true;
		} catch (RuntimeException e) {
			status.setText("Invalid configuration: " + (e instanceof IllegalArgumentException ? e.getMessage()
					: "Check the argument array and environment mapping JSON"));
			return false;
		}
	}

	private void persist() {
		owner.getSettings().setMcpConnections(java.util.Collections.list(connections.elements()));
		owner.getSettings().sync();
	}

	private void connect() {
		if (busy || !save()) {
			return;
		}
		McpConnection config = current.copy();
		manager.disconnect(config.id);
		refreshTools();
		long epoch = ++revision;
		setBusy(true);
		status.setText(McpText.str("connecting"));
		runner.submit(token -> {
			McpClient client = McpClient.connect(config, token);
			// The EDT owns publication; cancellation/disposal cannot leak a late connection.
			SwingUtilities.invokeLater(() -> {
				if (revision != epoch || !isDisplayable() || token.isCancelled()) {
					client.close();
					return;
				}
				manager.add(client);
				setBusy(false);
				refreshTools();
				clientTabs.setSelectedIndex(1);
				status.setText(McpText.str("connected"));
			});
			return true;
		}, ignored -> { }, error -> failure(epoch, error));
	}

	private void refreshTools() {
		tools.removeAllItems();
		McpClient client = current == null ? null : manager.client(current.id);
		if (client != null && !client.isClosed()) {
			client.tools().forEach(tool -> tools.addItem(tool.get("name").getAsString()));
		}
		showSchema();
	}

	private void showSchema() {
		McpClient client = current == null ? null : manager.client(current.id);
		String selected = (String) tools.getSelectedItem();
		output.setText("");
		if (client != null && selected != null) {
			client.tools().stream().filter(tool -> selected.equals(tool.get("name").getAsString())).findFirst()
					.ifPresent(tool -> output.setText(gson.toJson(tool)));
			output.setCaretPosition(0);
		}
	}

	private void invoke() {
		McpClient client = current == null ? null : manager.client(current.id);
		String selected = (String) tools.getSelectedItem();
		if (busy || client == null || selected == null) {
			return;
		}
		try {
			JsonObject params = JsonParser.parseString(arguments.getText()).getAsJsonObject();
			long epoch = ++revision;
			setBusy(true);
			status.setText(McpText.str("working"));
			runner.submit(token -> client.call(selected, params, token), result -> SwingUtilities.invokeLater(() -> {
				if (revision == epoch && isDisplayable()) {
					output.setText(gson.toJson(result));
					output.setCaretPosition(0);
					setBusy(false);
					status.setText(McpText.str("done"));
				}
			}), error -> failure(epoch, error));
		} catch (RuntimeException e) {
			status.setText("Tool arguments must be a JSON object");
		}
	}

	private void failure(long epoch, Exception error) {
		SwingUtilities.invokeLater(() -> {
			if (revision == epoch && isDisplayable()) {
				setBusy(false);
				status.setText(error instanceof java.io.IOException ? error.getMessage() : "MCP operation failed");
			}
		});
	}

	private void setBusy(boolean value) {
		busy = value;
		editing.forEach(component -> component.setEnabled(!value));
	}

	private void refreshServer() {
		McpServer server = manager.server();
		localStatus.setText(server == null ? McpText.str("stopped") : McpText.str("running") + " " + server.endpoint());
		String config = server == null ? "" : server.configuration();
		if (!localConfig.getText().equals(config)) {
			localConfig.setText(config);
			localConfig.setCaretPosition(0);
		}
	}

	@Override
	public void dispose() {
		revision++;
		runner.close();
		if (serverStatus != null) {
			serverStatus.stop();
		}
		super.dispose();
	}

	private static JButton button(String key, Runnable action) {
		JButton button = new JButton(McpText.str(key));
		button.addActionListener(event -> action.run());
		return button;
	}

	private static JTextArea note(String key) {
		JTextArea text = new JTextArea(McpText.str(key));
		text.setEditable(false);
		text.setFocusable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setOpaque(false);
		text.setRows(3);
		text.setColumns(30);
		return text;
	}

	private static void field(JPanel form, int row, String label, JComponent input) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = row * 2;
		c.gridx = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(6, 10, 3, 10);
		JLabel caption = new JLabel(McpText.str(label));
		caption.setLabelFor(input);
		form.add(caption, c);
		c.gridy++;
		if (input instanceof JTextField) {
			((JTextField) input).setColumns(20);
		}
		form.add(input, c);
	}

	private static JPanel connectionCard(JPanel fields, String hint) {
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.add(fields, BorderLayout.NORTH);
		JTextArea description = note(hint);
		description.setBorder(BorderFactory.createEmptyBorder(4, 10, 8, 10));
		panel.add(description, BorderLayout.SOUTH);
		return panel;
	}

	private static final class ScrollForm extends JPanel implements javax.swing.Scrollable {
		private ScrollForm() {
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return new Dimension(480, 450);
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
			return 24;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
			return Math.max(24, visible.height - 24);
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
