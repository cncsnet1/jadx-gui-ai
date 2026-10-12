package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import jadx.gui.ai.AiConfiguration;
import jadx.gui.ai.AiConnectionError;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.AiTaskRunner;
import jadx.gui.ai.AiText;
import jadx.gui.ai.OpenAiClient;
import jadx.gui.settings.JadxSettings;
import jadx.gui.ui.MainWindow;

/** Transactional configuration center: edits, routes and credentials commit together. */
public final class AiSettingDialog extends JDialog {
	private static final long serialVersionUID = 1L;
	private static final String[] TASKS = { "explain", "trace", "audit", "rename", "search" };
	private final JadxSettings settings;
	private final AiConfiguration draft;
	private final AiTaskRunner runner = new AiTaskRunner();
	private final DefaultListModel<AiProfile> profiles = new DefaultListModel<>();
	private final JList<AiProfile> profileList = new JList<>(profiles);
	private final Map<String, String> verified = new HashMap<>();
	private final JTextField name = new JTextField();
	private final JTextField url = new JTextField();
	private final JPasswordField key = new JPasswordField();
	private final JTextField environment = new JTextField();
	private final JCheckBox useEnvironment = new JCheckBox(AiText.str("use_env"));
	private final JCheckBox showKey = new JCheckBox(AiText.str("show_key"));
	private final JComboBox<String> model = new JComboBox<>();
	private final JSpinner tokens = new JSpinner(new SpinnerNumberModel(4096, 256, 32768, 256));
	private final JLabel resolvedUrl = new JLabel();
	private final JLabel editorTitle = new JLabel();
	private final JLabel defaultLabel = new JLabel();
	private final JPanel credentialFields = new JPanel(new CardLayout());
	private final JTextArea credentialHint = note("stored_key_hint", 2);
	private final JTextArea status = new JTextArea(3, 40);
	private final JProgressBar progress = new JProgressBar();
	private final JButton test = new JButton(AiText.str("test_response"));
	private final JButton discover = new JButton(AiText.str("discover_models"));
	private final JButton stop = new JButton(AiText.str("stop"));
	private final JPanel routing = new FormPanel();
	private final JTabbedPane tabs = new JTabbedPane();
	private AiProfile current;
	private boolean loading;
	private boolean busy;
	private long revision;

	public AiSettingDialog(Window owner, JadxSettings settings) {
		super(owner, AiText.str("settings_center"), ModalityType.APPLICATION_MODAL);
		this.settings = settings;
		this.draft = AiConfiguration.from(settings);
		model.setEditable(true);
		model.setPrototypeDisplayValue("model-name-with-a-version-suffix");
		profileList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		javax.swing.DefaultListCellRenderer modelRenderer = new javax.swing.DefaultListCellRenderer();
		modelRenderer.putClientProperty("html.disable", true);
		model.setRenderer(modelRenderer);
		buildUi();
		DocumentListener listener = new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				edited();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				edited();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				edited();
			}
		};
		for (JTextField field : List.of(name, url, key, environment, (JTextField) model.getEditor().getEditorComponent())) {
			field.getDocument().addDocumentListener(listener);
		}
		tokens.addChangeListener(e -> edited());
		useEnvironment.addActionListener(e -> {
			updateCredentialMode();
			edited();
		});
		char echo = key.getEchoChar();
		showKey.addActionListener(e -> key.setEchoChar(showKey.isSelected() ? (char) 0 : echo));
		draft.getProfiles().forEach(profiles::addElement);
		profileList.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && profileList.getSelectedValue() != null) {
				select(profileList.getSelectedValue());
			}
		});
		profileList.setSelectedValue(draft.getDefaultProfile(), true);
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setMinimumSize(new Dimension(840, 600));
		setSize(1020, 720);
		setLocationRelativeTo(owner);
	}

	private void buildUi() {
		JPanel root = new JPanel(new BorderLayout(0, 14));
		root.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));
		JPanel heading = new JPanel(new BorderLayout(0, 6));
		heading.add(title(AiText.str("settings_center"), 10), BorderLayout.NORTH);
		heading.add(new JLabel(AiText.str("settings_subtitle")), BorderLayout.CENTER);
		root.add(heading, BorderLayout.NORTH);

		tabs.addTab(AiText.str("connections"), buildConnections());
		JPanel routes = new JPanel(new BorderLayout(0, 18));
		routes.setBorder(BorderFactory.createEmptyBorder(18, 12, 12, 12));
		routes.add(note("routing_hint", 3), BorderLayout.NORTH);
		routes.add(new JScrollPane(routing), BorderLayout.CENTER);
		tabs.addTab(AiText.str("routing"), routes);
		tabs.addChangeListener(e -> {
			if (tabs.getSelectedIndex() == 1) {
				rebuildRoutes();
			}
		});
		root.add(tabs, BorderLayout.CENTER);

		JPanel footer = new JPanel(new BorderLayout());
		footer.add(new JLabel(AiText.str("draft_hint")), BorderLayout.CENTER);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
		JButton cancel = new JButton(AiText.str("cancel"));
		cancel.addActionListener(e -> dispose());
		JButton save = new JButton(AiText.str("save_configuration"));
		save.addActionListener(e -> save());
		buttons.add(cancel);
		buttons.add(save);
		footer.add(buttons, BorderLayout.EAST);
		root.add(footer, BorderLayout.SOUTH);
		setContentPane(root);
		getRootPane().setDefaultButton(save);
	}

	private JComponent buildConnections() {
		JPanel sidebar = new JPanel(new BorderLayout(0, 10));
		sidebar.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 12));
		JPanel listHeading = new JPanel(new BorderLayout());
		listHeading.add(title(AiText.str("saved_connections"), 1), BorderLayout.CENTER);
		JButton add = new JButton("+");
		add.setToolTipText(AiText.str("add_profile"));
		add.addActionListener(e -> addProfile(false));
		listHeading.add(add, BorderLayout.EAST);
		sidebar.add(listHeading, BorderLayout.NORTH);
		profileList.setFixedCellHeight(94);
		profileList.setCellRenderer((list, profile, index, selected, focus) -> {
			JPanel cell = new JPanel(new BorderLayout(0, 5));
			cell.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 10));
			cell.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
			Color color = selected ? list.getSelectionForeground() : list.getForeground();
			String marker = draft.getDefaultProfile() == profile ? " · " + AiText.str("default_badge") : "";
			JLabel title = plain(profile.getName() + marker);
			title.setFont(title.getFont().deriveFont(Font.BOLD));
			title.setForeground(color);
			JLabel detail = plain(profile.getModel().isEmpty() ? AiText.str("not_configured") : profile.getModel());
			detail.setForeground(color);
			cell.add(title, BorderLayout.NORTH);
			cell.add(detail, BorderLayout.CENTER);
			JLabel health = plain(AiText.str(verified.containsKey(profile.getId()) ? "verified" : "pending_verification"));
			health.setForeground(color);
			health.setFont(health.getFont().deriveFont(health.getFont().getSize2D() - 1));
			cell.add(health, BorderLayout.SOUTH);
			cell.setToolTipText(profile.destination() + (verified.containsKey(profile.getId()) ? " · " + AiText.str("verified") : ""));
			return cell;
		});
		sidebar.add(new JScrollPane(profileList), BorderLayout.CENTER);
		JPanel listActions = new JPanel(new GridLayout(1, 2, 6, 0));
		JButton duplicate = new JButton(AiText.str("duplicate"));
		duplicate.addActionListener(e -> addProfile(true));
		JButton remove = new JButton(AiText.str("remove_profile"));
		remove.addActionListener(e -> removeProfile());
		listActions.add(duplicate);
		listActions.add(remove);
		sidebar.add(listActions, BorderLayout.SOUTH);

		JPanel editor = new JPanel(new BorderLayout(0, 12));
		editor.setBorder(BorderFactory.createEmptyBorder(16, 16, 0, 0));
		JPanel editorHeading = new JPanel(new BorderLayout(0, 6));
		editorTitle.setFont(editorTitle.getFont().deriveFont(Font.BOLD, editorTitle.getFont().getSize2D() + 4));
		editorTitle.putClientProperty("html.disable", true);
		editorHeading.add(editorTitle, BorderLayout.CENTER);
		JButton makeDefault = new JButton(AiText.str("make_default"));
		makeDefault.addActionListener(e -> {
			if (current != null) {
				draft.setDefaultProfile(current.getId());
				updateDefault();
				profileList.repaint();
			}
		});
		editorHeading.add(makeDefault, BorderLayout.EAST);
		editorHeading.add(defaultLabel, BorderLayout.SOUTH);
		editor.add(editorHeading, BorderLayout.NORTH);

		JPanel form = new FormPanel();
		form.setBorder(BorderFactory.createEmptyBorder(6, 12, 12, 10));
		form.putClientProperty("FlatLaf.style", "background: $TextArea.background");
		for (JTextField field : List.of(name, url, key, environment)) {
			field.setPreferredSize(new Dimension(200, 30));
		}
		field(form, 0, "profile_name", name);
		url.putClientProperty("JTextField.placeholderText", "https://host/v1");
		field(form, 2, "api_url", url);
		resolvedUrl.putClientProperty("html.disable", true);
		resolvedUrl.setMinimumSize(new Dimension(1, resolvedUrl.getPreferredSize().height));
		resolvedUrl.setFont(resolvedUrl.getFont().deriveFont(resolvedUrl.getFont().getSize2D() - 1));
		row(form, 4, resolvedUrl, 0);
		JPanel storedKey = new JPanel(new BorderLayout(8, 0));
		storedKey.setOpaque(false);
		credentialFields.setOpaque(false);
		storedKey.add(key, BorderLayout.CENTER);
		storedKey.add(showKey, BorderLayout.EAST);
		credentialFields.add(storedKey, "stored");
		environment.putClientProperty("JTextField.placeholderText", "MY_AI_API_KEY");
		credentialFields.add(environment, "environment");
		JPanel credentialLabel = new JPanel(new BorderLayout());
		credentialLabel.setOpaque(false);
		credentialLabel.add(new JLabel(AiText.str("api_key")), BorderLayout.CENTER);
		credentialLabel.add(useEnvironment, BorderLayout.EAST);
		row(form, 5, credentialLabel, 10);
		row(form, 6, credentialFields, 5);
		row(form, 7, credentialHint, 4);
		JPanel modelRow = new JPanel(new BorderLayout(8, 0));
		modelRow.setOpaque(false);
		model.setPreferredSize(new Dimension(200, 30));
		modelRow.add(model, BorderLayout.CENTER);
		discover.addActionListener(e -> discoverModels());
		modelRow.add(discover, BorderLayout.EAST);
		field(form, 8, "model_selection", modelRow);
		model.setToolTipText(AiText.str("model_hint"));
		JToggleButton advanced = new JToggleButton(AiText.str("advanced"));
		advanced.putClientProperty("JButton.buttonType", "toolBarButton");
		row(form, 11, advanced, 4);
		JPanel limits = new JPanel(new BorderLayout(8, 5));
		limits.setOpaque(false);
		limits.add(new JLabel(AiText.str("max_tokens")), BorderLayout.WEST);
		limits.add(tokens, BorderLayout.CENTER);
		limits.add(note("output_hint", 2), BorderLayout.SOUTH);
		limits.setVisible(false);
		advanced.addActionListener(e -> {
			limits.setVisible(advanced.isSelected());
			form.revalidate();
		});
		row(form, 12, limits, 8);
		GridBagConstraints filler = new GridBagConstraints();
		filler.gridy = 13;
		filler.weighty = 1;
		form.add(new JPanel(), filler);
		JScrollPane scroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		editor.add(scroll, BorderLayout.CENTER);

		JPanel diagnostics = new JPanel(new BorderLayout(0, 8));
		diagnostics.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
				UIManager.getColor("Separator.foreground")), BorderFactory.createEmptyBorder(12, 0, 0, 0)));
		status.setLineWrap(true);
		status.setWrapStyleWord(true);
		status.setEditable(false);
		status.setOpaque(false);
		JScrollPane statusScroll = new JScrollPane(status, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		statusScroll.setBorder(BorderFactory.createEmptyBorder());
		statusScroll.setOpaque(false);
		statusScroll.getViewport().setOpaque(false);
		statusScroll.setPreferredSize(new Dimension(1, status.getFontMetrics(status.getFont()).getHeight() * 2 + 6));
		diagnostics.add(statusScroll, BorderLayout.CENTER);
		JPanel testRow = new JPanel(new BorderLayout(8, 0));
		progress.setIndeterminate(true);
		progress.setVisible(false);
		testRow.add(progress, BorderLayout.CENTER);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		test.addActionListener(e -> testConnection());
		stop.addActionListener(e -> {
			cancelPending();
			status.setText(AiText.str("test_cancelled"));
		});
		stop.setEnabled(false);
		actions.add(stop);
		actions.add(test);
		testRow.add(actions, BorderLayout.EAST);
		diagnostics.add(testRow, BorderLayout.SOUTH);
		editor.add(diagnostics, BorderLayout.SOUTH);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, editor);
		split.setBorder(null);
		split.setDividerLocation(230);
		split.setResizeWeight(0);
		sidebar.setMinimumSize(new Dimension(190, 100));
		editor.setMinimumSize(new Dimension(470, 100));
		return split;
	}

	private void select(AiProfile profile) {
		cancelPending();
		loading = true;
		current = profile;
		name.setText(profile.getName());
		url.setText(profile.getUrl());
		key.setText(profile.getApiKey());
		environment.setText(profile.getKeyEnvironment());
		useEnvironment.setSelected(!profile.getKeyEnvironment().isEmpty());
		model.removeAllItems();
		model.setSelectedItem(profile.getModel());
		tokens.setValue(profile.getOutputTokens());
		loading = false;
		updateCredentialMode();
		updateHeader();
		status.setText(verified.getOrDefault(profile.getId(), AiText.str("unverified")));
	}

	private void edited() {
		if (loading || current == null) {
			return;
		}
		if (busy) {
			cancelPending();
		}
		current.setName(name.getText());
		current.setUrl(url.getText());
		current.setApiKey(useEnvironment.isSelected() ? "" : new String(key.getPassword()));
		current.setKeyEnvironment(useEnvironment.isSelected() ? environment.getText() : "");
		current.setModel(String.valueOf(model.getEditor().getItem()));
		current.setOutputTokens((Integer) tokens.getValue());
		verified.remove(current.getId());
		status.setText(AiText.str("unverified"));
		updateHeader();
		profileList.repaint();
	}

	private void updateCredentialMode() {
		((CardLayout) credentialFields.getLayout()).show(credentialFields, useEnvironment.isSelected() ? "environment" : "stored");
		credentialHint.setText(AiText.str(useEnvironment.isSelected() ? "env_hint" : "stored_key_hint"));
	}

	private void updateHeader() {
		editorTitle.setText(current.getName().isEmpty() ? AiText.str("new_profile") : current.getName());
		try {
			resolvedUrl.setText("→ " + OpenAiClient.endpoint(current.getUrl()));
		} catch (IllegalArgumentException e) {
			resolvedUrl.setText(AiText.str("endpoint_hint"));
		}
		resolvedUrl.setToolTipText(resolvedUrl.getText());
		updateDefault();
	}

	private void updateDefault() {
		defaultLabel.setText(AiText.str(current == draft.getDefaultProfile() ? "default_connection_hint" : "connection_hint"));
	}

	private void addProfile(boolean duplicate) {
		if (profiles.size() >= 20) {
			status.setText(AiText.str("profile_limit"));
			return;
		}
		AiProfile profile = duplicate && current != null ? current.duplicate() : new AiProfile();
		profile.setName(
				(duplicate && current != null ? current.getName() + " · " : "") + AiText.str("new_profile") + " " + (profiles.size() + 1));
		draft.getProfiles().add(profile);
		profiles.addElement(profile);
		profileList.setSelectedValue(profile, true);
		name.requestFocusInWindow();
		name.selectAll();
	}

	private void removeProfile() {
		if (current == null || profiles.size() <= 1) {
			status.setText(AiText.str("keep_one_profile"));
			return;
		}
		int index = profileList.getSelectedIndex();
		AiProfile removed = current;
		draft.remove(removed);
		profiles.removeElement(removed);
		verified.remove(removed.getId());
		profileList.setSelectedIndex(Math.min(index, profiles.size() - 1));
	}

	private void rebuildRoutes() {
		routing.removeAll();
		for (int i = 0; i < TASKS.length; i++) {
			String task = TASKS[i];
			JComboBox<RouteChoice> combo = new JComboBox<>();
			combo.setPrototypeDisplayValue(new RouteChoice(null, "Connection name · model-version-name"));
			javax.swing.DefaultListCellRenderer routeRenderer = new javax.swing.DefaultListCellRenderer();
			routeRenderer.putClientProperty("html.disable", true);
			combo.setRenderer(routeRenderer);
			combo.addItem(new RouteChoice(null, AiText.str("inherit_default")));
			for (AiProfile profile : draft.getProfiles()) {
				combo.addItem(new RouteChoice(profile.getId(), profile.getName() + " · " + profile.getModel()));
				if (profile.getId().equals(draft.getTaskProfileId(task))) {
					combo.setSelectedIndex(combo.getItemCount() - 1);
				}
			}
			combo.addActionListener(e -> draft.setTaskProfile(task, ((RouteChoice) combo.getSelectedItem()).id));
			field(routing, i * 3, task, combo);
			row(routing, i * 3 + 2, note("route_" + task, 2), 5);
		}
		GridBagConstraints fill = new GridBagConstraints();
		fill.gridy = 20;
		fill.weighty = 1;
		routing.add(new JPanel(), fill);
		routing.revalidate();
		routing.repaint();
	}

	private static final class RouteChoice {
		private final String id;
		private final String label;

		RouteChoice(String id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private OpenAiClient client(boolean requireModel) {
		try {
			if (requireModel) {
				current.validate();
			}
			return new OpenAiClient(current.getUrl(), current.resolveApiKey(), Duration.ofSeconds(35), Duration.ofSeconds(15));
		} catch (IllegalArgumentException e) {
			status.setText(e.getMessage());
			return null;
		}
	}

	private void discoverModels() {
		OpenAiClient client = client(false);
		if (client == null) {
			return;
		}
		long id = begin(AiText.str("reading_models"));
		String selected = current.getModel();
		runner.submit(client::models, ids -> SwingUtilities.invokeLater(() -> {
			if (!finish(id)) {
				return;
			}
			loading = true;
			model.removeAllItems();
			ids.forEach(model::addItem);
			model.setSelectedItem(selected);
			loading = false;
			status.setText(String.format(AiText.str("models_loaded"), ids.size()));
		}), error -> SwingUtilities.invokeLater(() -> {
			if (finish(id)) {
				status.setText(AiConnectionError.describe(error) + "\n" + AiText.str("manual_model"));
			}
		}));
	}

	private void testConnection() {
		OpenAiClient client = client(true);
		if (client == null) {
			return;
		}
		String modelName = current.getModel();
		verified.remove(current.getId());
		profileList.repaint();
		long id = begin(AiText.str("testing_stream"));
		long started = System.nanoTime();
		runner.submit(token -> client.stream(modelName, List.of(Map.of("role", "user", "content", "Reply OK.")),
				0.3, 32, token, chunk -> {
				}), value -> SwingUtilities.invokeLater(() -> {
					if (!finish(id)) {
						return;
					}
					String message =
							String.format(AiText.str("verified_response"), modelName, (System.nanoTime() - started) / 1_000_000_000.0);
					verified.put(current.getId(), message);
					status.setText(message);
					profileList.repaint();
				}), error -> SwingUtilities.invokeLater(() -> {
					if (finish(id)) {
						status.setText(AiConnectionError.describe(error));
					}
				}));
	}

	private long begin(String message) {
		cancelPending();
		setBusy(true);
		status.setText(message);
		return revision;
	}

	private boolean finish(long id) {
		if (id != revision || !isDisplayable()) {
			return false;
		}
		setBusy(false);
		return true;
	}

	private void cancelPending() {
		revision++;
		runner.cancel();
		setBusy(false);
	}

	private void setBusy(boolean value) {
		busy = value;
		test.setEnabled(!value);
		discover.setEnabled(!value);
		stop.setEnabled(value);
		progress.setVisible(value);
	}

	private void save() {
		try {
			tokens.commitEdit();
		} catch (java.text.ParseException e) {
			tabs.setSelectedIndex(0);
			status.setText(AiText.str("output_invalid"));
			return;
		}
		Set<String> names = new HashSet<>();
		for (AiProfile profile : draft.getProfiles()) {
			try {
				profile.validate();
				if (!names.add(profile.getName().toLowerCase(Locale.ROOT))) {
					throw new IllegalArgumentException(AiText.str("duplicate_name"));
				}
			} catch (IllegalArgumentException e) {
				tabs.setSelectedIndex(0);
				profileList.setSelectedValue(profile, true);
				status.setText(e.getMessage());
				return;
			}
		}
		settings.setAiConfiguration(draft);
		settings.sync();
		if (getOwner() instanceof MainWindow) {
			((MainWindow) getOwner()).getAiWorkbench().refreshConfiguration();
		}
		dispose();
	}

	private static JLabel title(String text, int extraSize) {
		JLabel label = plain(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + extraSize));
		return label;
	}

	private static JLabel plain(String text) {
		JLabel label = new JLabel(text);
		label.putClientProperty("html.disable", true);
		return label;
	}

	private static JTextArea note(String key, int lines) {
		JTextArea text = new JTextArea(AiText.str(key));
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setEditable(false);
		text.setFocusable(false);
		text.setOpaque(false);
		Dimension size = new Dimension(1, text.getFontMetrics(text.getFont()).getHeight() * lines + 4);
		text.setPreferredSize(size);
		text.setMinimumSize(size);
		return text;
	}

	private static void field(JPanel panel, int row, String name, JComponent component) {
		JLabel label = new JLabel(AiText.str(name));
		label.setLabelFor(component);
		row(panel, row, label, 10);
		row(panel, row + 1, component, 5);
	}

	private static void row(JPanel panel, int row, JComponent component, int gap) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.insets = new Insets(gap, 0, 0, 6);
		panel.add(component, c);
	}

	@Override
	public void dispose() {
		cancelPending();
		runner.close();
		super.dispose();
	}

	private static final class FormPanel extends JPanel implements javax.swing.Scrollable {
		private static final long serialVersionUID = 1L;

		FormPanel() {
			super(new GridBagLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return new Dimension(500, getPreferredSize().height);
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
			return visible.height - 16;
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
