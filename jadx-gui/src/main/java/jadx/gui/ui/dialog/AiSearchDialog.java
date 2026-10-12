package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import jadx.api.JavaClass;
import jadx.gui.ai.AiClassSearch;
import jadx.gui.ai.AiClassSearch.Match;
import jadx.gui.ai.AiConfiguration;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.AiTaskRunner;
import jadx.gui.ai.AiText;
import jadx.gui.ai.OpenAiClient;
import jadx.gui.treemodel.JClass;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.panel.ContentPanel;
import jadx.gui.utils.ILoadListener;
import jadx.gui.utils.UiUtils;
import jadx.gui.utils.ui.WorkbenchUi;

/** Modeless discovery workspace. Transport and response validation live in AiClassSearch. */
public class AiSearchDialog extends CommonDialog {
	private static final long serialVersionUID = -5105405456969134105L;

	public enum SearchPreset {
		TEXT, CLASS, COMMENT
	}

	private final AiTaskRunner runner = new AiTaskRunner();
	private final JTextArea query = new JTextArea(3, 50);
	private final JTextField packageFilter = new JTextField();
	private final JCheckBox activeClass = new JCheckBox(AiText.str("search_current_class"));
	private final JComboBox<AiProfile> profiles = new JComboBox<>();
	private final JTextArea plan = textArea(2);
	private final JTextArea status = textArea(2);
	private final JButton search = new JButton(AiText.str("search_run"));
	private final JButton stop = new JButton(AiText.str("stop"));
	private final JButton settings = new JButton(AiText.str("settings"));
	private final JButton jump = new JButton(AiText.str("search_open"));
	private final JProgressBar progress = new JProgressBar();
	private final JLabel resultCount = WorkbenchUi.title(AiText.str("search_results"), 1);
	private final JTextField resultFilter = new JTextField(18);
	private final DefaultListModel<Match> resultModel = new DefaultListModel<>();
	private final JList<Match> results = new JList<>(resultModel);
	private final JTextArea reason = textArea(5);
	private final CardLayout resultCards = new CardLayout();
	private final JPanel resultBody = new JPanel(resultCards);
	private final JLabel emptyTitle = WorkbenchUi.title(AiText.str("search_empty_title"), 6);
	private final JTextArea emptyHint = textArea(3);
	private final Timer filterTimer = new Timer(180, e -> updatePlan());
	private final ILoadListener loadListener;
	private final ChangeListener tabListener = e -> updatePlan();
	private JClass rootClass;
	private Map<String, JavaClass> classes = Map.of();
	private List<Match> matches = List.of();
	private boolean busy;
	private boolean indexed;
	private boolean closed;
	private long generation;

	public static void search(MainWindow window, SearchPreset preset) {
		show(window, "", "", false);
	}

	public static void searchInActiveTab(MainWindow window, SearchPreset preset) {
		show(window, "", "", true);
	}

	public static void searchText(MainWindow window, String text) {
		show(window, text, "", false);
	}

	public static void searchPackage(MainWindow window, String packageName) {
		show(window, "", packageName, false);
	}

	private static void show(MainWindow window, String text, String packageName, boolean currentOnly) {
		AiSearchDialog dialog = new AiSearchDialog(window);
		dialog.query.setText(text);
		dialog.packageFilter.setText(packageName);
		dialog.activeClass.setSelected(currentOnly);
		dialog.setVisible(true);
		dialog.query.requestFocusInWindow();
		dialog.loadIndex();
	}

	private AiSearchDialog(MainWindow window) {
		super(window);
		setTitle(AiText.str("search_title"));
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		ContentPanel content = window.getTabbedPane().getSelectedContentPanel();
		rootClass = content == null ? null : content.getNode().getRootClass();
		filterTimer.setRepeats(false);
		buildUi();
		refreshProfiles();
		UiUtils.addEscapeShortCutToDispose(this);
		getRootPane().setDefaultButton(search);
		setMinimumSize(new Dimension(860, 680));
		setSize(1060, 820);
		if (!window.getSettings().loadWindowPos(this)) {
			setLocationRelativeTo(window);
		}
		loadListener = loaded -> {
			if (!loaded) {
				generation++;
				runner.cancel();
				SwingUtilities.invokeLater(this::dispose);
				return true;
			}
			return false;
		};
		window.addLoadListener(loadListener);
		window.getTabbedPane().addChangeListener(tabListener);
	}

	private void buildUi() {
		JPanel page = new JPanel(new BorderLayout(0, 16));
		page.setBorder(BorderFactory.createEmptyBorder(22, 24, 16, 24));
		JPanel top = WorkbenchUi.transparent();
		JPanel heading = WorkbenchUi.transparent();
		heading.add(WorkbenchUi.title(AiText.str("search_title"), 11), BorderLayout.NORTH);
		heading.add(WorkbenchUi.mutedLabel(AiText.str("search_subtitle")), BorderLayout.CENTER);
		top.add(heading, BorderLayout.NORTH);
		JPanel composer = WorkbenchUi.card(16);
		JPanel prompt = WorkbenchUi.transparent();
		prompt.add(labelFor("search_prompt", query), BorderLayout.NORTH);
		query.setLineWrap(true);
		query.setWrapStyleWord(true);
		query.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		query.setToolTipText(AiText.str("search_shortcut"));
		prompt.add(new JScrollPane(query), BorderLayout.CENTER);
		JPanel examples = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
		examples.setOpaque(false);
		for (String key : List.of("search_example_auth", "search_example_network", "search_example_crypto")) {
			JButton example = new JButton(AiText.str(key));
			example.putClientProperty("JButton.buttonType", "toolBarButton");
			example.addActionListener(e -> {
				if (!busy) {
					query.setText(AiText.str(key + "_query"));
					query.requestFocusInWindow();
				}
			});
			examples.add(example);
		}
		prompt.add(examples, BorderLayout.SOUTH);
		composer.add(prompt, BorderLayout.NORTH);
		JPanel scope = WorkbenchUi.transparent();
		scope.add(labelFor("search_package", packageFilter), BorderLayout.WEST);
		packageFilter.putClientProperty("JTextField.placeholderText", AiText.str("search_package_hint"));
		scope.add(packageFilter, BorderLayout.CENTER);
		activeClass.setOpaque(false);
		activeClass.setToolTipText(rootClass == null ? AiText.str("search_no_active") : rootClass.getFullName());
		scope.add(activeClass, BorderLayout.EAST);
		composer.add(scope, BorderLayout.CENTER);
		JPanel connection = WorkbenchUi.transparent();
		JPanel modelRow = WorkbenchUi.transparent();
		modelRow.add(labelFor("search_connection", profiles), BorderLayout.WEST);
		DefaultListCellRenderer renderer = new DefaultListCellRenderer();
		renderer.putClientProperty("html.disable", true);
		profiles.setRenderer((list, value, index, selected, focus) -> {
			JLabel label = (JLabel) renderer.getListCellRendererComponent(list, value, index, selected, focus);
			label.setText(value == null ? "" : value.getName() + "  /  " + value.getModel());
			return label;
		});
		modelRow.add(profiles, BorderLayout.CENTER);
		modelRow.add(settings, BorderLayout.EAST);
		connection.add(modelRow, BorderLayout.NORTH);
		connection.add(plan, BorderLayout.CENTER);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		actions.setOpaque(false);
		actions.add(WorkbenchUi.mutedLabel(AiText.str("search_shortcut")));
		actions.add(stop);
		actions.add(search);
		WorkbenchUi.primary(search);
		connection.add(actions, BorderLayout.SOUTH);
		composer.add(connection, BorderLayout.SOUTH);
		top.add(composer, BorderLayout.CENTER);
		page.add(top, BorderLayout.NORTH);
		JPanel resultPane = WorkbenchUi.transparent();
		JPanel resultHeading = WorkbenchUi.transparent();
		resultHeading.add(resultCount, BorderLayout.WEST);
		resultFilter.putClientProperty("JTextField.placeholderText", AiText.str("search_filter"));
		resultFilter.getAccessibleContext().setAccessibleName(AiText.str("search_filter"));
		resultHeading.add(resultFilter, BorderLayout.EAST);
		resultPane.add(resultHeading, BorderLayout.NORTH);
		buildResults();
		resultPane.add(resultBody, BorderLayout.CENTER);
		page.add(resultPane, BorderLayout.CENTER);
		JPanel footer = WorkbenchUi.transparent();
		progress.setPreferredSize(new Dimension(100, 4));
		footer.add(progress, BorderLayout.NORTH);
		footer.add(status, BorderLayout.CENTER);
		page.add(footer, BorderLayout.SOUTH);
		setContentPane(page);
		search.addActionListener(e -> startSearch());
		stop.addActionListener(e -> {
			generation++;
			runner.cancel();
			setBusy(false);
			status.setText(AiText.str("search_stopped"));
			if (matches.isEmpty()) {
				empty(AiText.str("search_stopped_title"), AiText.str("search_stopped"));
			}
		});
		settings.addActionListener(e -> {
			new AiSettingDialog(mainWindow, mainWindow.getSettings()).setVisible(true);
			refreshProfiles();
		});
		profiles.addActionListener(e -> updatePlan());
		activeClass.addActionListener(e -> updatePlan());
		onChange(packageFilter, () -> filterTimer.restart());
		onChange(resultFilter, this::filterResults);
		query.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "search");
		query.getActionMap().put("search", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				startSearch();
			}
		});
		setBusy(false);
	}

	private void buildResults() {
		JPanel empty = WorkbenchUi.card(28);
		JPanel message = WorkbenchUi.transparent();
		message.add(emptyTitle, BorderLayout.NORTH);
		emptyHint.setText(AiText.str("search_empty_hint"));
		message.add(emptyHint, BorderLayout.CENTER);
		empty.add(message, BorderLayout.NORTH);
		resultBody.add(empty, "empty");
		results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		results.setFixedCellHeight(68);
		results.setCellRenderer((list, match, index, selected, focus) -> {
			JPanel row = new JPanel(new BorderLayout(12, 5));
			row.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
			row.setBackground(UIManager.getColor(selected ? "List.selectionBackground" : "List.background"));
			String name = match.getClassName();
			int dot = name.lastIndexOf('.');
			JLabel title = WorkbenchUi.title(dot < 0 ? name : name.substring(dot + 1), 1);
			JLabel path = new JLabel(dot < 0 ? AiText.str("search_default_package") : name.substring(0, dot));
			path.putClientProperty("html.disable", true);
			title.setForeground(UIManager.getColor(selected ? "List.selectionForeground" : "List.foreground"));
			path.setForeground(selected ? title.getForeground() : WorkbenchUi.muted());
			row.add(title, BorderLayout.NORTH);
			row.add(path, BorderLayout.CENTER);
			return row;
		});
		results.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				Match match = results.getSelectedValue();
				reason.setText(match == null
						? ""
						: match.getClassName() + "\n\n" + match.getReason()
								+ "\n\n" + AiText.str("search_name_evidence"));
				reason.setCaretPosition(0);
				jump.setEnabled(match != null);
			}
		});
		results.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int index = results.locationToIndex(e.getPoint());
				if (e.getClickCount() == 2 && index >= 0 && results.getCellBounds(index, index).contains(e.getPoint())) {
					jump();
				}
			}
		});
		results.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-class");
		results.getActionMap().put("open-class", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				jump();
			}
		});
		JPanel detail = WorkbenchUi.card(16);
		detail.add(WorkbenchUi.title(AiText.str("search_match_reason"), 0), BorderLayout.NORTH);
		JScrollPane reasonScroll = new JScrollPane(reason);
		reasonScroll.setBorder(BorderFactory.createEmptyBorder());
		reasonScroll.setOpaque(false);
		reasonScroll.getViewport().setOpaque(false);
		detail.add(reasonScroll, BorderLayout.CENTER);
		jump.addActionListener(e -> jump());
		jump.setEnabled(false);
		detail.add(jump, BorderLayout.SOUTH);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(results), detail);
		split.setBorder(BorderFactory.createEmptyBorder());
		split.setResizeWeight(0.55);
		split.setDividerLocation(520);
		split.setContinuousLayout(true);
		split.getLeftComponent().setMinimumSize(new Dimension(260, 120));
		detail.setMinimumSize(new Dimension(260, 120));
		resultBody.add(split, "matches");
	}

	private void refreshProfiles() {
		AiProfile selected = (AiProfile) profiles.getSelectedItem();
		AiConfiguration configuration = AiConfiguration.from(mainWindow.getSettings());
		AiProfile preferred = selected == null ? configuration.forTask("search") : configuration.find(selected.getId());
		profiles.removeAllItems();
		for (AiProfile profile : configuration.getProfiles()) {
			profiles.addItem(profile);
			if (preferred != null && preferred.getId().equals(profile.getId())) {
				profiles.setSelectedItem(profile);
			}
		}
		updatePlan();
	}

	private void loadIndex() {
		status.setText(AiText.str("search_indexing"));
		long id = ++generation;
		runner.submit(token -> {
			Map<String, JavaClass> index = new LinkedHashMap<>();
			for (JavaClass cls : mainWindow.getWrapper().getIncludedClassesWithInners()) {
				token.check();
				index.put(cls.getFullName(), cls);
			}
			return index;
		}, value -> deliver(id, () -> {
			classes = value;
			indexed = true;
			updatePlan();
			status.setText(AiText.str("search_ready"));
		}), error -> deliver(id, () -> status.setText(error.getMessage())));
	}

	private List<String> candidates() {
		List<String> names = new ArrayList<>();
		String prefix = AiClassSearch.packagePrefix(packageFilter.getText());
		for (Map.Entry<String, JavaClass> entry : classes.entrySet()) {
			if (entry.getKey().startsWith(prefix)
					&& (!activeClass.isSelected() || rootClass != null
							&& entry.getValue().getTopParentClass().equals(rootClass.getCls()))) {
				names.add(entry.getKey());
			}
		}
		names.sort(String::compareTo);
		return names;
	}

	private void updatePlan() {
		if (busy || closed) {
			return;
		}
		ContentPanel content = mainWindow.getTabbedPane().getSelectedContentPanel();
		rootClass = content == null ? null : content.getNode().getRootClass();
		activeClass.setEnabled(rootClass != null);
		activeClass.setToolTipText(rootClass == null ? AiText.str("search_no_active") : rootClass.getFullName());
		AiProfile profile = (AiProfile) profiles.getSelectedItem();
		try {
			List<String> names = candidates();
			plan.setText(String.format(AiText.str("search_plan"), names.size(), AiClassSearch.batches(names).size(),
					profile == null ? "—" : profile.destination()));
			search.setEnabled(indexed && !names.isEmpty() && profile != null);
		} catch (IllegalArgumentException e) {
			plan.setText(e.getMessage());
			search.setEnabled(false);
		}
	}

	private void startSearch() {
		if (busy || !search.isEnabled()) {
			return;
		}
		String text = query.getText().trim();
		if (text.isEmpty() || text.length() > 4000) {
			status.setText(AiText.str("search_query_limit"));
			query.requestFocusInWindow();
			return;
		}
		try {
			AiProfile profile = ((AiProfile) profiles.getSelectedItem()).copy();
			profile.validate();
			OpenAiClient client = new OpenAiClient(profile.getUrl(), profile.resolveApiKey());
			List<String> names = candidates();
			matches = List.of();
			resultFilter.setText("");
			filterResults();
			empty(AiText.str("search_working_title"), AiText.str("search_working_hint"));
			setBusy(true);
			long id = ++generation;
			runner.submit(token -> AiClassSearch.search(client, profile, text, names, token, state -> deliver(id, () -> {
				matches = state.getMatches();
				filterResults();
				progress.setMaximum(state.getTotal());
				progress.setValue(state.getCompleted());
				status.setText(String.format(AiText.str("search_progress"), state.getCompleted(), state.getTotal(), matches.size()));
			})), value -> deliver(id, () -> {
				matches = value;
				setBusy(false);
				filterResults();
				status.setText(String.format(AiText.str("search_done"), matches.size()));
				if (matches.isEmpty()) {
					empty(AiText.str("search_no_results"), AiText.str("search_no_results_hint"));
				}
			}), error -> deliver(id, () -> {
				setBusy(false);
				status.setText(AiText.str("search_failed") + " " + error.getMessage());
				if (matches.isEmpty()) {
					empty(AiText.str("search_failed_title"), AiText.str("search_failed_hint"));
				}
			}));
		} catch (Exception e) {
			status.setText(AiText.str("search_config_error") + " " + e.getMessage());
		}
	}

	private void filterResults() {
		Match selected = results.getSelectedValue();
		String filter = resultFilter.getText().trim().toLowerCase(Locale.ROOT);
		resultModel.clear();
		for (Match match : matches) {
			if ((match.getClassName() + " " + match.getReason()).toLowerCase(Locale.ROOT).contains(filter)) {
				resultModel.addElement(match);
			}
		}
		resultCount.setText(AiText.str("search_results") + "  ·  " + resultModel.size() + " / " + matches.size());
		if (!matches.isEmpty()) {
			if (resultModel.isEmpty()) {
				empty(AiText.str("search_filter_empty"), AiText.str("search_filter_empty_hint"));
				return;
			}
			resultCards.show(resultBody, "matches");
			if (selected != null && resultModel.contains(selected)) {
				results.setSelectedValue(selected, true);
			} else if (!resultModel.isEmpty()) {
				results.setSelectedIndex(0);
			}
		}
	}

	private void empty(String title, String hint) {
		emptyTitle.setText(title);
		emptyHint.setText(hint);
		resultCards.show(resultBody, "empty");
	}

	private void setBusy(boolean value) {
		busy = value;
		query.setEditable(!value);
		packageFilter.setEnabled(!value);
		activeClass.setEnabled(!value);
		profiles.setEnabled(!value);
		settings.setEnabled(!value);
		stop.setEnabled(value);
		search.setEnabled(!value && indexed);
		progress.setVisible(value);
		if (value) {
			progress.setValue(0);
		} else {
			updatePlan();
		}
	}

	private void jump() {
		Match match = results.getSelectedValue();
		if (match != null && !closed) {
			JavaClass cls = classes.get(match.getClassName());
			if (cls != null) {
				mainWindow.getTabsController().codeJump(mainWindow.getCacheObject().getNodeCache().makeFrom(cls));
				mainWindow.toFront();
			}
		}
	}

	private void deliver(long id, Runnable action) {
		SwingUtilities.invokeLater(() -> {
			if (!closed && generation == id) {
				action.run();
			}
		});
	}

	@Override
	public void dispose() {
		closed = true;
		generation++;
		filterTimer.stop();
		runner.close();
		mainWindow.removeLoadListener(loadListener);
		mainWindow.getTabbedPane().removeChangeListener(tabListener);
		super.dispose();
	}

	private static JTextArea textArea(int rows) {
		JTextArea area = new JTextArea(rows, 1);
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		return area;
	}

	private static JLabel labelFor(String key, javax.swing.JComponent component) {
		JLabel label = new JLabel(AiText.str(key));
		label.setLabelFor(component);
		return label;
	}

	private static void onChange(JTextField field, Runnable action) {
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				action.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				action.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				action.run();
			}
		});
	}
}
