package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.AiRequestContext;
import jadx.gui.ai.AiText;
import jadx.gui.ui.MainWindow;

/** Read-only inspection of the exact snapshot that the send button will submit. */
public final class AiContextDialog extends JDialog {
	private static final long serialVersionUID = 1L;
	private boolean send;

	public AiContextDialog(MainWindow owner, AiProfile profile, AiRequestContext context) {
		super(owner, AiText.str("request_preview_title"), ModalityType.APPLICATION_MODAL);
		JPanel root = new JPanel(new BorderLayout(0, 14));
		root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
		JPanel heading = new JPanel(new BorderLayout(0, 8));
		JLabel title = plain(profile.getName() + " · " + profile.getModel());
		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 5));
		heading.add(title, BorderLayout.NORTH);
		heading.add(plain(String.format(AiText.str("request_summary"), profile.destination(), context.getEvidence().size(),
				context.getCharacterCount(), context.getHistoryTurns())), BorderLayout.SOUTH);
		root.add(heading, BorderLayout.NORTH);

		RSyntaxTextArea code = new RSyntaxTextArea();
		code.setEditable(false);
		code.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
		code.setCodeFoldingEnabled(true);
		owner.getEditorTheme().apply(code);
		code.setFont(owner.getSettings().getFont());
		List<AiEvidence> evidence = context.getEvidence();
		String[] titles = new String[evidence.size()];
		for (int i = 0; i < evidence.size(); i++) {
			titles[i] = "[S" + (i + 1) + "] " + evidence.get(i).getTitle();
		}
		JList<String> sources = new JList<>(titles);
		sources.putClientProperty("html.disable", true);
		javax.swing.DefaultListCellRenderer renderer = new javax.swing.DefaultListCellRenderer();
		renderer.putClientProperty("html.disable", true);
		sources.setCellRenderer(renderer);
		JLabel sourceState = new JLabel();
		sourceState.putClientProperty("html.disable", true);
		sources.addListSelectionListener(e -> {
			int index = sources.getSelectedIndex();
			if (index < 0) {
				return;
			}
			AiEvidence source = evidence.get(index);
			code.setText(source.getCode());
			code.setCaretPosition(0);
			sourceState.setText(source.getTitle() + " · " + source.getCode().length() + " " + AiText.str("characters")
					+ (source.isTruncated() ? " · " + AiText.str("truncated") : ""));
			sourceState.setToolTipText(source.getTitle());
		});
		JPanel sourcePane = new JPanel(new BorderLayout(0, 8));
		sourcePane.add(sourceState, BorderLayout.NORTH);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(sources), new JScrollPane(code));
		split.setDividerLocation(245);
		split.setResizeWeight(0.25);
		sourcePane.add(split, BorderLayout.CENTER);
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab(AiText.str("source_snapshot"), sourcePane);
		JTextArea request = new JTextArea(context.preview());
		request.setEditable(false);
		request.setLineWrap(true);
		request.setWrapStyleWord(true);
		request.setCaretPosition(0);
		tabs.addTab(AiText.str("complete_request"), new JScrollPane(request));
		root.add(tabs, BorderLayout.CENTER);
		JPanel footer = new JPanel(new BorderLayout(8, 0));
		footer.add(new JLabel(AiText.str("snapshot_hint")), BorderLayout.CENTER);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING));
		JButton back = new JButton(AiText.str("back_to_edit"));
		back.addActionListener(e -> dispose());
		JButton submit = new JButton(AiText.str("send_snapshot"));
		submit.addActionListener(e -> {
			send = true;
			dispose();
		});
		actions.add(back);
		actions.add(submit);
		footer.add(actions, BorderLayout.EAST);
		root.add(footer, BorderLayout.SOUTH);
		setContentPane(root);
		getRootPane().setDefaultButton(submit);
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setMinimumSize(new Dimension(850, 570));
		setSize(1080, 720);
		setLocationRelativeTo(owner);
		sources.setSelectedIndex(0);
	}

	private static JLabel plain(String text) {
		JLabel label = new JLabel(text);
		label.putClientProperty("html.disable", true);
		return label;
	}

	public boolean shouldSend() {
		return send;
	}
}
