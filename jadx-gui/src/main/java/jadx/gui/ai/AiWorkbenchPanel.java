package jadx.gui.ai;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.api.JadxDecompiler;
import jadx.api.JavaNode;
import jadx.api.data.ICodeComment;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.settings.JadxProject;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JRenameNode;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.codearea.AbstractCodeContentPanel;
import jadx.gui.ui.codearea.CodeArea;
import jadx.gui.ui.dialog.AiContextDialog;
import jadx.gui.ui.dialog.AiInvestigationDialog;
import jadx.gui.ui.dialog.AiSettingDialog;
import jadx.gui.ui.dialog.CommentDialog;
import jadx.gui.ui.dialog.RenameDialog;
import jadx.gui.ui.panel.ContentPanel;

/** Source-first workbench. Project switches invalidate every pending callback. */
public final class AiWorkbenchPanel extends JPanel {
	private static final long serialVersionUID = 1L;
	private static final String[] TASKS = { "explain", "trace", "audit", "rename" };
	private final MainWindow window;
	private final AiTaskRunner runner = new AiTaskRunner();
	private final JLabel selection = new JLabel();
	private final JLabel resultSource = new JLabel();
	private final JLabel resultProvider = new JLabel();
	private final JCheckBox callers = new JCheckBox(AiText.str("callers"));
	private final JComboBox<String> task = new JComboBox<>();
	private final JComboBox<String> profileChoice = new JComboBox<>();
	private final JLabel destination = new JLabel();
	private AiConfiguration configuration;
	private boolean refreshingProfiles;
	private final JTextArea question = new JTextArea(3, 24);
	private final JTextArea status = new JTextArea();
	private final JEditorPane answer = new JEditorPane("text/html", "");
	private final JProgressBar progress = new JProgressBar();
	private final DefaultListModel<AiEvidence> sources = new DefaultListModel<>();
	private final JList<AiEvidence> sourceList = new JList<>(sources);
	private final DefaultListModel<AiRecord> records = new DefaultListModel<>();
	private final JList<AiRecord> recordList = new JList<>(records);
	private final JButton send = button("send", this::request);
	private final JButton stop = button("stop", this::cancel);
	private final JButton save = button("save", this::saveResult);
	private final JButton comment = button("comment", this::reviewComment);
	private final JButton rename = button("apply_name", this::reviewName);
	private final JButton export = button("export", this::exportResult);
	private final JButton investigate = button("investigate", this::investigate);
	private final JButton preview = button("context_preview", () -> request(true));
	private final Timer followTimer;
	private final Timer renderTimer;
	private final Parser markdown = Parser.builder().build();
	private final HtmlRenderer html = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build();
	private final List<Map<String, String>> history = new ArrayList<>();
	private String historyContext;
	private CodeArea currentArea;
	private AiEvidence current;
	private AiRecord result;
	private List<AiEvidence> resultEvidence = Collections.emptyList();
	private StringBuffer output = new StringBuffer();
	private int renderedLength = -1;
	private long epoch;
	private boolean busy;

	public AiWorkbenchPanel(MainWindow window) {
		super(new BorderLayout(0, 12));
		this.window = window;
		setBorder(BorderFactory.createEmptyBorder(14, 14, 10, 14));
		setPreferredSize(new Dimension(390, 650));
		setMinimumSize(new Dimension(285, 200));
		putClientProperty("FlatLaf.style", "background: $Panel.background");
		followTimer = new Timer(180, e -> capture());
		followTimer.setRepeats(false);
		renderTimer = new Timer(250, e -> renderAnswer());
		buildUi();
		refreshConfiguration();
		window.getTabbedPane().addChangeListener(e -> SwingUtilities.invokeLater(this::followSelectedTab));
		window.addLoadListener(loaded -> {
			JadxProject project = window.getProject();
			Runnable update = () -> {
				if (window.getProject() != project) {
					return;
				}
				if (!loaded) {
					resetProject();
				} else {
					refreshRecords();
					followSelectedTab();
				}
			};
			if (SwingUtilities.isEventDispatchThread()) {
				update.run();
			} else {
				SwingUtilities.invokeLater(update);
			}
			return false;
		});
	}

	private void buildUi() {
		JPanel heading = new JPanel(new BorderLayout(8, 8));
		JLabel title = new JLabel(AiText.str("title"));
		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3));
		heading.add(title, BorderLayout.CENTER);
		JButton settings = button("settings", () -> new AiSettingDialog(window, window.getSettings()).setVisible(true));
		settings.putClientProperty("JButton.buttonType", "toolBarButton");
		JButton hide = new JButton("×");
		hide.setToolTipText(AiText.str("hide"));
		hide.putClientProperty("JButton.buttonType", "toolBarButton");
		hide.addActionListener(e -> window.setAiWorkbenchVisible(false));
		JPanel headingActions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 2, 0));
		headingActions.add(settings);
		headingActions.add(hide);
		heading.add(headingActions, BorderLayout.EAST);
		heading.add(new JLabel(AiText.str("subtitle")), BorderLayout.SOUTH);
		add(heading, BorderLayout.NORTH);

		JTabbedPane tabs = new JTabbedPane();
		JPanel analysis = new JPanel(new BorderLayout(0, 8));
		JPanel context = new JPanel();
		context.setLayout(new BoxLayout(context, BoxLayout.Y_AXIS));
		selection.setText(AiText.str("empty"));
		selection.putClientProperty("html.disable", true);
		selection.setAlignmentX(LEFT_ALIGNMENT);
		selection.setMaximumSize(new Dimension(Integer.MAX_VALUE, selection.getPreferredSize().height));
		context.add(selection);
		context.add(Box.createVerticalStrut(6));
		callers.setToolTipText(AiText.str("callers_tip"));
		callers.setAlignmentX(LEFT_ALIGNMENT);
		context.add(callers);
		JPanel modes = new JPanel(new BorderLayout(6, 0));
		modes.setAlignmentX(LEFT_ALIGNMENT);
		for (String key : TASKS) {
			task.addItem(AiText.str(key));
		}
		modes.add(task, BorderLayout.CENTER);
		modes.add(button("pin", this::pinSource), BorderLayout.EAST);
		context.add(Box.createVerticalStrut(8));
		context.add(modes);
		profileChoice.setAlignmentX(LEFT_ALIGNMENT);
		profileChoice.setMaximumSize(new Dimension(Integer.MAX_VALUE, profileChoice.getPreferredSize().height));
		profileChoice.setToolTipText(AiText.str("profile_switch_hint"));
		DefaultListCellRenderer profileRenderer = new DefaultListCellRenderer();
		profileRenderer.putClientProperty("html.disable", true);
		profileChoice.setRenderer(profileRenderer);
		profileChoice.addActionListener(e -> {
			if (!refreshingProfiles) {
				updateDestination();
			}
		});
		task.addActionListener(e -> updateDestination());
		context.add(Box.createVerticalStrut(8));
		context.add(profileChoice);
		destination.putClientProperty("html.disable", true);
		destination.setAlignmentX(LEFT_ALIGNMENT);
		destination.setMaximumSize(new Dimension(Integer.MAX_VALUE, destination.getPreferredSize().height + 4));
		destination.setFont(destination.getFont().deriveFont(destination.getFont().getSize2D() - 1));
		context.add(Box.createVerticalStrut(4));
		context.add(destination);
		analysis.add(context, BorderLayout.NORTH);

		answer.setEditable(false);
		answer.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
		answer.addHyperlinkListener(e -> {
			if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription().matches("source:S[1-9][0-9]*")) {
				int index = Integer.parseInt(e.getDescription().substring(8)) - 1;
				if (index < resultEvidence.size()) {
					jump(resultEvidence.get(index));
				}
			}
		});
		JPanel resultPane = new JPanel(new BorderLayout(0, 6));
		resultSource.putClientProperty("html.disable", true);
		JPanel resultHeading = new JPanel(new BorderLayout(0, 3));
		resultHeading.add(resultSource, BorderLayout.NORTH);
		resultProvider.putClientProperty("html.disable", true);
		resultProvider.setFont(resultProvider.getFont().deriveFont(resultProvider.getFont().getSize2D() - 1));
		resultHeading.add(resultProvider, BorderLayout.SOUTH);
		resultPane.add(resultHeading, BorderLayout.NORTH);
		resultPane.add(new JScrollPane(answer), BorderLayout.CENTER);
		JPanel resultActions = new JPanel(new GridLayout(2, 2, 4, 4));
		resultActions.add(save);
		resultActions.add(export);
		resultActions.add(comment);
		resultActions.add(rename);
		JPanel resultFooter = new JPanel(new BorderLayout(0, 6));
		resultFooter.add(investigate, BorderLayout.NORTH);
		resultFooter.add(resultActions, BorderLayout.SOUTH);
		resultPane.add(resultFooter, BorderLayout.SOUTH);
		JPanel evidencePane = new JPanel(new BorderLayout());
		DefaultListCellRenderer sourceRenderer = new DefaultListCellRenderer();
		sourceRenderer.putClientProperty("html.disable", true);
		sourceList.setCellRenderer((list, source, index, selected, focus) -> {
			JLabel label = (JLabel) sourceRenderer.getListCellRendererComponent(list, source, index, selected, focus);
			label.putClientProperty("html.disable", true);
			label.setText("[S" + (index + 1) + "] " + shortTitle(source.getTitle())
					+ (source.isTruncated() ? " · " + AiText.str("truncated") : ""));
			label.setToolTipText(source.getTitle());
			label.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
			return label;
		});
		DefaultListCellRenderer recordRenderer = new DefaultListCellRenderer();
		recordRenderer.putClientProperty("html.disable", true);
		recordList.setCellRenderer((list, record, index, selected, focus) -> {
			JLabel label = (JLabel) recordRenderer.getListCellRendererComponent(list, record, index, selected, focus);
			label.putClientProperty("html.disable", true);
			label.setToolTipText(record.toString());
			return label;
		});
		evidencePane.add(new JScrollPane(sourceList), BorderLayout.CENTER);
		evidencePane.add(button("jump", () -> jump(sourceList.getSelectedValue())), BorderLayout.SOUTH);
		JSplitPane details = new JSplitPane(JSplitPane.VERTICAL_SPLIT, resultPane, evidencePane);
		details.setResizeWeight(0.85);
		details.setDividerLocation(330);
		evidencePane.setMinimumSize(new Dimension(100, 80));
		analysis.add(details, BorderLayout.CENTER);
		sourceList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					jump(sourceList.getSelectedValue());
				}
			}
		});

		JPanel notes = new JPanel(new BorderLayout(0, 8));
		notes.add(new JLabel(AiText.str("select")), BorderLayout.NORTH);
		notes.add(new JScrollPane(recordList), BorderLayout.CENTER);
		JPanel noteActions = new JPanel(new FlowLayout(FlowLayout.LEADING));
		JButton open = button("analyze", () -> {
			AiRecord record = recordList.getSelectedValue();
			if (record != null) {
				cancel();
				history.clear();
				historyContext = null;
				showResult(record);
				tabs.setSelectedIndex(0);
			}
		});
		noteActions.add(open);
		noteActions.add(button("delete", () -> {
			AiRecord record = recordList.getSelectedValue();
			if (record != null) {
				window.getProject().removeAiRecord(record);
				refreshRecords();
			}
		}));
		notes.add(noteActions, BorderLayout.SOUTH);
		tabs.addTab(AiText.str("analyze"), analysis);
		tabs.addTab(AiText.str("records"), notes);
		add(tabs, BorderLayout.CENTER);

		JPanel composer = new JPanel(new BorderLayout(0, 7));
		composer.add(new JLabel(AiText.str("question")), BorderLayout.NORTH);
		question.setLineWrap(true);
		question.setWrapStyleWord(true);
		question.putClientProperty("JTextField.placeholderText", AiText.str("question"));
		question.setToolTipText(AiText.str("question"));
		question.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "ai-send");
		question.getActionMap().put("ai-send", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				request();
			}
		});
		composer.add(new JScrollPane(question), BorderLayout.CENTER);
		JPanel controls = new JPanel(new BorderLayout(8, 0));
		progress.setIndeterminate(true);
		progress.setVisible(false);
		controls.add(progress, BorderLayout.CENTER);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		buttons.add(preview);
		buttons.add(stop);
		buttons.add(send);
		send.putClientProperty("JButton.buttonType", "roundRect");
		controls.add(buttons, BorderLayout.EAST);
		status.setEditable(false);
		status.setFocusable(false);
		status.setLineWrap(true);
		status.setWrapStyleWord(true);
		status.setOpaque(false);
		status.setRows(2);
		JPanel footer = new JPanel(new BorderLayout(0, 6));
		footer.add(controls, BorderLayout.NORTH);
		footer.add(status, BorderLayout.SOUTH);
		composer.add(footer, BorderLayout.SOUTH);
		add(composer, BorderLayout.SOUTH);
		setBusy(false);
		status.setText(AiText.str("ready"));
	}

	public void follow(CodeArea area) {
		if (!area.isDisposed() && area.getContentPanel() == window.getTabbedPane().getSelectedContentPanel()) {
			currentArea = area;
			followTimer.restart();
		}
	}

	private void followSelectedTab() {
		ContentPanel panel = window.getTabbedPane().getSelectedContentPanel();
		if (panel instanceof AbstractCodeContentPanel && ((AbstractCodeContentPanel) panel).getCodeArea() instanceof CodeArea) {
			follow((CodeArea) ((AbstractCodeContentPanel) panel).getCodeArea());
		} else {
			currentArea = null;
			capture();
		}
	}

	private void capture() {
		current = AiContext.capture(currentArea);
		selection.setText(current == null ? AiText.str("empty") : shortTitle(current.getTitle()));
		if (current != null && current.isTruncated()) {
			selection.setText(selection.getText() + " · " + AiText.str("truncated"));
		}
		selection.setToolTipText(current == null ? null : current.getTitle());
		send.setEnabled(!busy && current != null);
		preview.setEnabled(!busy && current != null);
		if (!busy && current != null && !resultEvidence.isEmpty()
				&& current.getNode().equals(resultEvidence.get(0).getNode())
				&& !current.getFingerprint().equals(resultEvidence.get(0).getFingerprint())) {
			status.setText(AiText.str("source_changed"));
		}
	}

	public void open(CodeArea area, String kind) {
		if (busy) {
			cancel();
		}
		window.setAiWorkbenchVisible(true);
		currentArea = area;
		capture();
		for (int i = 0; i < TASKS.length; i++) {
			if (TASKS[i].equals(kind)) {
				task.setSelectedIndex(i);
			}
		}
		question.setText("");
		request();
	}

	private void request() {
		request(false);
	}

	public void refreshConfiguration() {
		AiProfile selected = configuration == null || profileChoice.getSelectedIndex() <= 0 ? null : selectedProfile();
		configuration = AiConfiguration.from(window.getSettings());
		refreshingProfiles = true;
		profileChoice.removeAllItems();
		profileChoice.addItem(AiText.str("task_default"));
		for (AiProfile profile : configuration.getProfiles()) {
			profileChoice.addItem(profile.getName() + " · " + profile.getModel());
			if (selected != null && selected.getId().equals(profile.getId())) {
				profileChoice.setSelectedIndex(profileChoice.getItemCount() - 1);
			}
		}
		refreshingProfiles = false;
		updateDestination();
	}

	private AiProfile selectedProfile() {
		int index = profileChoice.getSelectedIndex() - 1;
		return index < 0 ? configuration.forTask(TASKS[task.getSelectedIndex()]) : configuration.getProfiles().get(index);
	}

	private void updateDestination() {
		if (configuration == null || busy) {
			return;
		}
		AiProfile profile = selectedProfile();
		destination.setText(profile.getName() + " · " + profile.destination());
		destination.setToolTipText(profile.getName() + " · " + profile.getModel() + " · " + profile.destination());
	}

	private void request(boolean inspect) {
		if (busy) {
			return;
		}
		capture();
		if (current == null) {
			return;
		}
		AiProfile profile = selectedProfile().copy();
		if (profile.getUrl().isBlank() || profile.getModel().isBlank()) {
			status.setText(AiText.str("configure"));
			new AiSettingDialog(window, window.getSettings()).setVisible(true);
			return;
		}
		try {
			profile.validate();
		} catch (IllegalArgumentException e) {
			status.setText(e.getMessage());
			return;
		}
		AiEvidence primary = current;
		JadxProject project = window.getProject();
		JadxDecompiler decompiler = window.getWrapper().getDecompiler();
		boolean includeCallers = callers.isSelected();
		String kind = TASKS[task.getSelectedIndex()];
		String prompt = question.getText().trim();
		if (prompt.isEmpty()) {
			prompt = AiText.str("prompt_" + kind);
		}
		String userPrompt = prompt;
		long id = ++epoch;
		setBusy(true);
		status.setText(AiText.str("preparing_context"));
		destination.setText(profile.getName() + " · " + profile.getModel());
		destination.setToolTipText(profile.destination());
		runner.submit(token -> AiContext.collect(decompiler, primary, includeCallers, token), evidence -> SwingUtilities.invokeLater(() -> {
			if (epoch != id || window.getProject() != project) {
				return;
			}
			String contextId = AiRequestContext.identity(profile, kind, evidence);
			List<Map<String, String>> previous =
					Objects.equals(historyContext, contextId) ? new ArrayList<>(history) : Collections.emptyList();
			AiRequestContext context = new AiRequestContext(evidence, previous, userPrompt, !kind.equals("rename"));
			if (inspect) {
				AiContextDialog dialog = new AiContextDialog(window, profile, context);
				dialog.setVisible(true);
				if (epoch != id || window.getProject() != project) {
					return;
				}
				if (!dialog.shouldSend()) {
					setBusy(false);
					status.setText(AiText.str("snapshot_not_sent"));
					return;
				}
			}
			startAnalysis(id, project, profile, kind, userPrompt, contextId, context);
		}), failure -> showFailure(id, project, failure));
	}

	private void startAnalysis(long id, JadxProject project, AiProfile profile, String kind, String userPrompt,
			String contextId, AiRequestContext context) {
		final AiClient client;
		try {
			client = new OpenAiClient(profile.getUrl(), profile.resolveApiKey());
		} catch (IllegalArgumentException e) {
			showFailure(id, project, e);
			return;
		}
		if (!Objects.equals(historyContext, contextId)) {
			history.clear();
		}
		historyContext = contextId;
		AiEvidence primary = context.getEvidence().get(0);
		StringBuffer buffer = new StringBuffer();
		output = buffer;
		result = null;
		resultEvidence = context.getEvidence();
		setSources(resultEvidence);
		resultSource.setText(AiText.str("selection") + ": " + primary.getTitle());
		resultSource.setToolTipText(primary.getTitle());
		resultProvider.setText(profile.getName() + " · " + profile.getModel());
		resultProvider.setToolTipText(profile.destination());
		renderedLength = -1;
		setBusy(true);
		status.setText(AiText.str("running"));
		renderTimer.start();
		runner.submit(token -> {
			String text = client.stream(profile.getModel(), context.getMessages(), 0.3, profile.getOutputTokens(), token, buffer::append);
			return new AiRecord(AiText.str(kind) + " · " + primary.getTitle(), text, context.getEvidence(), profile);
		}, completed -> SwingUtilities.invokeLater(() -> {
			if (epoch != id || window.getProject() != project) {
				return;
			}
			setBusy(false);
			showResult(completed);
			history.add(message("user", userPrompt));
			history.add(message("assistant", completed.getAnswer()));
			while (history.size() > 4 || history.stream().mapToInt(m -> m.get("content").length()).sum() > 16_000) {
				history.remove(0);
				history.remove(0);
			}
			question.setText("");
			status.setText(AiText.str("complete"));
		}), failure -> showFailure(id, project, failure));
	}

	private void showFailure(long id, JadxProject project, Exception failure) {
		SwingUtilities.invokeLater(() -> {
			if (epoch != id || window.getProject() != project) {
				return;
			}
			setBusy(false);
			renderAnswer();
			status.setText(AiText.str("failed") + AiConnectionError.describe(failure));
		});
	}

	private static Map<String, String> message(String role, String content) {
		return Map.of("role", role, "content", content);
	}

	private void showResult(AiRecord record) {
		result = record;
		resultEvidence = record.getEvidence();
		output = new StringBuffer(record.getAnswer());
		renderedLength = -1;
		setSources(resultEvidence);
		resultSource.setText(record.toString());
		resultSource.setToolTipText(record.toString() + " · " + record.getProviderSummary());
		resultProvider.setText(record.getProviderSummary());
		resultProvider.setToolTipText(record.getProviderSummary());
		investigate.setText(record.getFindings().isEmpty()
				? AiText.str("investigate")
				: String.format(AiText.str("investigate_count"), record.getFindings().size()));
		investigate.setToolTipText(record.getFindings().isEmpty() ? AiText.str("findings_empty") : AiText.str("findings_hint"));
		renderAnswer();
		setBusy(false);
	}

	private void renderAnswer() {
		String text = output.toString();
		if (text.length() == renderedLength) {
			return;
		}
		renderedLength = text.length();
		String displayed = text.isEmpty() && !busy ? AiText.str("welcome") : result == null ? text : result.getNarrative();
		String rendered = html.render(markdown.parse(displayed)).replaceAll("(?is)<img\\b[^>]*>", "");
		for (int i = 1; i <= resultEvidence.size(); i++) {
			rendered = rendered.replace("[S" + i + "]", "<a href='source:S" + i + "'>[S" + i + "]</a>");
		}
		Color foreground = UIManager.getColor("TextArea.foreground");
		Color background = UIManager.getColor("TextArea.background");
		answer.setText("<html><body style='font-family:sans-serif; padding:8px;color:" + color(foreground)
				+ ";background:" + color(background) + "'>" + rendered + "</body></html>");
	}

	public void refreshTheme() {
		renderedLength = -1;
		renderAnswer();
	}

	private static String color(Color color) {
		return String.format("#%06x", color == null ? 0 : color.getRGB() & 0xffffff);
	}

	private void setSources(List<AiEvidence> evidence) {
		sources.clear();
		for (AiEvidence source : evidence) {
			sources.addElement(source);
		}
		if (!sources.isEmpty()) {
			sourceList.setSelectedIndex(0);
		}
	}

	private void setBusy(boolean value) {
		busy = value;
		question.setEditable(!value);
		task.setEnabled(!value);
		callers.setEnabled(!value);
		profileChoice.setEnabled(!value);
		preview.setEnabled(!value && current != null);
		progress.setVisible(value);
		stop.setEnabled(value);
		send.setEnabled(!value && current != null);
		save.setEnabled(!value && result != null);
		export.setEnabled(!value && result != null);
		investigate.setEnabled(!value && result != null);
		comment.setEnabled(!value && result != null);
		rename.setEnabled(!value && suggestedName() != null);
		if (!value) {
			renderTimer.stop();
			updateDestination();
		}
	}

	private void cancel() {
		epoch++;
		runner.cancel();
		setBusy(false);
		renderAnswer();
		status.setText(AiText.str("cancelled"));
	}

	private void resetProject() {
		cancel();
		result = null;
		followTimer.stop();
		currentArea = null;
		current = null;
		resultEvidence = Collections.emptyList();
		sources.clear();
		records.clear();
		history.clear();
		historyContext = null;
		output = new StringBuffer();
		resultSource.setText("");
		resultProvider.setText("");
		investigate.setText(AiText.str("investigate"));
		selection.setText(AiText.str("empty"));
		renderedLength = -1;
		renderAnswer();
		setBusy(false);
		status.setText(AiText.str("ready"));
	}

	public void close() {
		resetProject();
		runner.close();
	}

	private void saveResult() {
		if (result != null) {
			store(result);
		}
	}

	private void investigate() {
		if (result != null && !busy) {
			new AiInvestigationDialog(window, result, this::refreshRecords, this::jump).setVisible(true);
		}
	}

	private void exportResult() {
		if (result == null) {
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(AiText.str("export"));
		chooser.setFileFilter(new FileNameExtensionFilter("Markdown (*.md)", "md"));
		FileNameExtensionFilter htmlFilter = new FileNameExtensionFilter("HTML (*.html)", "html");
		chooser.addChoosableFileFilter(htmlFilter);
		chooser.setAcceptAllFileFilterUsed(false);
		chooser.setSelectedFile(new java.io.File("ai-analysis.md"));
		if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path path = chooser.getSelectedFile().toPath();
		boolean asHtml = chooser.getFileFilter() == htmlFilter || path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".html");
		String extension = asHtml ? ".html" : ".md";
		if (!path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(extension)) {
			path = path.resolveSibling(path.getFileName().toString().replaceFirst("(?i)\\.(md|html)$", "") + extension);
		}
		if (Files.exists(path) && JOptionPane.showConfirmDialog(window, AiText.str("overwrite"),
				AiText.str("export"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			Files.writeString(path, asHtml ? AiReport.html(result) : AiReport.markdown(result), StandardCharsets.UTF_8);
			status.setText(AiText.str("exported") + path);
		} catch (IOException e) {
			status.setText(AiText.str("failed") + e.getMessage());
		}
	}

	private void pinSource() {
		capture();
		if (current != null) {
			store(new AiRecord(current.getTitle(), "```java\n" + current.getCode() + "\n```", Collections.singletonList(current)));
		}
	}

	private void store(AiRecord record) {
		if (!window.getProject().getAiRecords().contains(record) && window.getProject().getAiRecords().size() >= 100) {
			status.setText(AiText.str("limit"));
			return;
		}
		window.getProject().addAiRecord(record);
		refreshRecords();
		status.setText(AiText.str("saved"));
	}

	private void refreshRecords() {
		records.clear();
		window.getProject().getAiRecords().forEach(records::addElement);
	}

	private void jump(AiEvidence evidence) {
		if (evidence == null) {
			return;
		}
		JavaNode node = AiContext.resolve(window.getWrapper().getDecompiler(), evidence.getNode());
		if (node == null) {
			status.setText(AiText.str("missing"));
			return;
		}
		JNode jNode = window.getCacheObject().getNodeCache().makeFrom(node);
		window.getTabsController().codeJump(jNode);
	}

	private boolean canApply() {
		capture();
		if (result == null || resultEvidence.isEmpty() || current == null
				|| !current.getNode().equals(resultEvidence.get(0).getNode())
				|| !current.getFingerprint().equals(resultEvidence.get(0).getFingerprint())) {
			status.setText(AiText.str("stale"));
			return false;
		}
		return true;
	}

	private void reviewComment() {
		if (!canApply()) {
			return;
		}
		JadxNodeRef ref = current.getNode();
		JadxCodeData codeData = window.getProject().getCodeData();
		ICodeComment existing = codeData == null
				? null
				: codeData.getComments().stream()
						.filter(c -> ref.equals(c.getNodeRef()) && c.getCodeRef() == null).findFirst().orElse(null);
		String draft = (existing == null ? "" : existing.getComment() + "\n\n") + result.getNarrative();
		CommentDialog.showDraft(currentArea, existing == null ? new JadxCodeComment(ref, "") : existing,
				existing != null, draft);
	}

	private String suggestedName() {
		if (result == null) {
			return null;
		}
		try {
			String text = result.getAnswer().trim().replaceAll("^```(?:json)?\\s*|\\s*```$", "");
			JsonObject json = JsonParser.parseString(text).getAsJsonObject();
			return json.get("name").getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void reviewName() {
		String name = suggestedName();
		if (name == null || !canApply()) {
			return;
		}
		JavaNode javaNode = AiContext.resolve(window.getWrapper().getDecompiler(), current.getNode());
		if (javaNode == null) {
			status.setText(AiText.str("missing"));
			return;
		}
		JNode node = window.getCacheObject().getNodeCache().makeFrom(javaNode);
		if (node instanceof JRenameNode && ((JRenameNode) node).canRename() && ((JRenameNode) node).isValidName(name)) {
			RenameDialog.rename(window, (JRenameNode) node, name);
		} else {
			JOptionPane.showMessageDialog(window, AiText.str("stale"));
		}
	}

	private static JButton button(String key, Runnable action) {
		JButton button = new JButton(AiText.str(key));
		button.addActionListener(e -> action.run());
		return button;
	}

	private static String shortTitle(String title) {
		int last = title.lastIndexOf('.');
		int previous = last < 0 ? -1 : title.lastIndexOf('.', last - 1);
		return title.substring(previous + 1);
	}
}
