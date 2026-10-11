package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiFinding;
import jadx.gui.ai.AiRecord;
import jadx.gui.ai.AiText;
import jadx.gui.settings.JadxProject;
import jadx.gui.ui.MainWindow;

/**
 * Review model claims beside their immutable source snapshots. No network calls or automatic source
 * edits.
 */
public final class AiInvestigationDialog extends JDialog {
	private static final long serialVersionUID = 1L;
	private final MainWindow owner;
	private final JadxProject project;
	private final AiRecord record;
	private final Runnable onSaved;
	private final Consumer<AiEvidence> navigate;
	private final List<AiFinding> findings;
	private final List<AiFinding.Review> draftReviews = new ArrayList<>();
	private final List<String> draftNotes = new ArrayList<>();
	private final JList<AiFinding> list;
	private final JTextPane reasoning = new JTextPane();
	private final JTextArea note = new JTextArea(4, 24);
	private final JComboBox<AiFinding.Review> review = new JComboBox<>(AiFinding.Review.values());
	private final JComboBox<AiFinding.Citation> citations = new JComboBox<>();
	private final RSyntaxTextArea code = new RSyntaxTextArea();
	private final JTextArea quote = text();
	private final JTextArea sourceState = text();
	private final JTextArea message = text();
	private final JLabel summary = plain("");
	private final JLabel reviewedAt = plain("");
	private final JButton jump = new JButton(AiText.str("jump_live_source"));
	private final JButton save = new JButton(AiText.str("save_review"));
	private int selected = -1;

	public AiInvestigationDialog(MainWindow owner, AiRecord record, Runnable onSaved, Consumer<AiEvidence> navigate) {
		super(owner, AiText.str("investigate"), ModalityType.APPLICATION_MODAL);
		this.owner = owner;
		this.project = owner.getProject();
		this.record = record;
		this.onSaved = onSaved;
		this.navigate = navigate;
		this.findings = record.getFindings();
		for (AiFinding finding : findings) {
			draftReviews.add(finding.getReview());
			draftNotes.add(finding.getReviewNote());
		}
		list = new JList<>(findings.toArray(new AiFinding[0]));
		buildUi();
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				closeReview();
			}
		});
		setMinimumSize(new Dimension(920, 650));
		setSize(1180, 820);
		setLocationRelativeTo(owner);
		if (!findings.isEmpty()) {
			list.setSelectedIndex(0);
		}
	}

	private void buildUi() {
		JPanel root = new JPanel(new BorderLayout(0, 16));
		root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
		JPanel heading = new JPanel(new BorderLayout(0, 6));
		JLabel title = plain(AiText.str("investigate"));
		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 8));
		heading.add(title, BorderLayout.NORTH);
		JTextArea provenance = text();
		provenance.setText(record + "\n" + record.getProviderSummary());
		heading.add(provenance, BorderLayout.CENTER);
		heading.add(summary, BorderLayout.SOUTH);
		root.add(heading, BorderLayout.NORTH);
		updateSummary();

		if (findings.isEmpty()) {
			JTextArea empty = text();
			empty.setText(AiText.str("findings_empty"));
			root.add(empty, BorderLayout.CENTER);
			save.setEnabled(false);
		} else {
			list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			list.setFixedCellHeight(116);
			list.setCellRenderer((items, value, index, active, focus) -> findingCard(value, index, active));
			list.addListSelectionListener(e -> {
				if (!e.getValueIsAdjusting()) {
					selectFinding();
				}
			});
			JScrollPane rail = new JScrollPane(list);
			rail.setMinimumSize(new Dimension(240, 100));
			JPanel reviewPane = new JPanel(new BorderLayout(0, 10));
			JTabbedPane tabs = new JTabbedPane();
			tabs.addTab(AiText.str("finding_reasoning"), buildReasoning());
			tabs.addTab(AiText.str("finding_evidence"), buildEvidence());
			reviewPane.add(tabs, BorderLayout.CENTER);
			reviewPane.add(buildReview(), BorderLayout.SOUTH);
			JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rail, reviewPane);
			split.setDividerLocation(295);
			split.setResizeWeight(0.27);
			root.add(split, BorderLayout.CENTER);
		}

		JPanel footer = new JPanel(new BorderLayout(12, 0));
		message.setText(AiText.str("review_draft_hint"));
		message.setRows(2);
		footer.add(message, BorderLayout.CENTER);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
		JButton close = new JButton(AiText.str("close_investigation"));
		close.addActionListener(e -> closeReview());
		save.addActionListener(e -> saveReview());
		actions.add(close);
		actions.add(save);
		footer.add(actions, BorderLayout.EAST);
		root.add(footer, BorderLayout.SOUTH);
		setContentPane(root);
	}

	private JScrollPane buildReasoning() {
		reasoning.setEditable(false);
		reasoning.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
		return new JScrollPane(reasoning);
	}

	private JPanel buildEvidence() {
		JPanel evidence = new JPanel(new BorderLayout(0, 10));
		evidence.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
		JPanel top = new JPanel(new BorderLayout(0, 8));
		DefaultListCellRenderer citationRenderer = new DefaultListCellRenderer();
		citationRenderer.putClientProperty("html.disable", true);
		citations.setRenderer((items, citation, index, active, focus) -> {
			JLabel label = (JLabel) citationRenderer.getListCellRendererComponent(items, citation, index, active, focus);
			if (citation != null) {
				label.setText(citation + " · "
						+ AiText.str(citation.matches(record.getEvidence()) ? "quote_found_short" : "quote_missing_short"));
			}
			return label;
		});
		citations.addActionListener(e -> showCitation());
		top.add(citations, BorderLayout.NORTH);
		sourceState.setRows(3);
		top.add(new JScrollPane(sourceState), BorderLayout.CENTER);
		quote.setRows(2);
		quote.setFont(owner.getSettings().getFont());
		top.add(new JScrollPane(quote), BorderLayout.SOUTH);
		evidence.add(top, BorderLayout.NORTH);
		code.setEditable(false);
		code.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
		owner.getEditorTheme().apply(code);
		code.setFont(owner.getSettings().getFont());
		evidence.add(new org.fife.ui.rtextarea.RTextScrollPane(code), BorderLayout.CENTER);
		jump.addActionListener(e -> {
			AiFinding.Citation citation = (AiFinding.Citation) citations.getSelectedItem();
			AiEvidence source = citation == null ? null : citation.resolve(record.getEvidence());
			if (source != null && owner.getProject() == project && canClose()) {
				navigate.accept(source);
				dispose();
			}
		});
		evidence.add(jump, BorderLayout.SOUTH);
		return evidence;
	}

	private JPanel buildReview() {
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
				UIManager.getColor("Component.borderColor")), BorderFactory.createEmptyBorder(12, 14, 0, 14)));
		JPanel top = new JPanel(new BorderLayout(10, 0));
		top.add(plain(AiText.str("human_review")), BorderLayout.WEST);
		DefaultListCellRenderer renderer = new DefaultListCellRenderer();
		review.setRenderer((items, value, index, active, focus) -> {
			JLabel label = (JLabel) renderer.getListCellRendererComponent(items, value, index, active, focus);
			if (value != null) {
				label.setText(reviewLabel(value));
			}
			return label;
		});
		top.add(review, BorderLayout.CENTER);
		panel.add(top, BorderLayout.NORTH);
		note.setLineWrap(true);
		note.setWrapStyleWord(true);
		note.putClientProperty("JTextField.placeholderText", AiText.str("review_note_hint"));
		note.setToolTipText(AiText.str("review_note_hint"));
		panel.add(new JScrollPane(note), BorderLayout.CENTER);
		panel.add(reviewedAt, BorderLayout.SOUTH);
		return panel;
	}

	private Component findingCard(AiFinding finding, int index, boolean active) {
		JPanel card = new JPanel(new BorderLayout(0, 7));
		Color background = UIManager.getColor(active ? "List.selectionBackground" : "List.background");
		Color foreground = UIManager.getColor(active ? "List.selectionForeground" : "List.foreground");
		card.setBackground(background);
		card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, active ? 3 : 0, 1, 0,
				UIManager.getColor(active ? "Component.accentColor" : "Separator.foreground")),
				BorderFactory.createEmptyBorder(12, 13, 12, 13)));
		JLabel label = plain(String.format("%02d", index + 1) + " · " + kindLabel(finding.getKind()));
		label.setForeground(foreground);
		card.add(label, BorderLayout.NORTH);
		JTextArea title = text();
		title.setText(finding.getTitle());
		title.setForeground(foreground);
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		card.add(title, BorderLayout.CENTER);
		JLabel state = plain(reviewLabel(draftReviews.get(index)));
		state.setForeground(foreground);
		card.add(state, BorderLayout.SOUTH);
		return card;
	}

	private void keepDraft() {
		if (selected >= 0) {
			draftReviews.set(selected, (AiFinding.Review) review.getSelectedItem());
			draftNotes.set(selected, note.getText());
		}
	}

	private void selectFinding() {
		keepDraft();
		selected = list.getSelectedIndex();
		if (selected < 0) {
			return;
		}
		AiFinding finding = findings.get(selected);
		showReasoning(finding);
		review.setSelectedItem(draftReviews.get(selected));
		note.setText(draftNotes.get(selected));
		note.setCaretPosition(0);
		reviewedAt.setText(finding.getReviewedAt() == 0
				? AiText.str("not_reviewed")
				: AiText.str("reviewed_at") + Instant.ofEpochMilli(finding.getReviewedAt()));
		citations.removeAllItems();
		finding.getCitations().forEach(citations::addItem);
		showCitation();
		list.repaint();
	}

	private void showCitation() {
		AiFinding.Citation citation = (AiFinding.Citation) citations.getSelectedItem();
		AiEvidence source = citation == null ? null : citation.resolve(record.getEvidence());
		quote.setText(citation == null ? "" : citation.getQuote());
		quote.setCaretPosition(0);
		jump.setEnabled(source != null);
		citations.setEnabled(citations.getItemCount() > 0);
		code.setText(source == null ? "" : source.getCode());
		code.getHighlighter().removeAllHighlights();
		code.setCaretPosition(0);
		if (source == null) {
			sourceState.setText(AiText.str(citation == null ? "finding_no_citations" : "finding_bad_reference"));
			return;
		}
		boolean matched = citation.matches(record.getEvidence());
		sourceState.setText(source.getTitle() + (source.isTruncated() ? " · " + AiText.str("truncated") : "") + "\n"
				+ AiText.str(matched ? "quote_found" : "quote_missing") + "\n" + AiText.str("snapshot_review_hint"));
		if (matched) {
			int offset = source.getCode().indexOf(citation.getQuote());
			code.select(offset, offset + citation.getQuote().length());
			try {
				code.getHighlighter().addHighlight(offset, offset + citation.getQuote().length(),
						new DefaultHighlighter.DefaultHighlightPainter(code.getSelectionColor()));
			} catch (BadLocationException e) {
				throw new IllegalStateException("Cannot highlight captured quote", e);
			}
		}
		sourceState.setCaretPosition(0);
	}

	private boolean saveReview() {
		keepDraft();
		if (owner.getProject() != project) {
			message.setText(AiText.str("missing"));
			return false;
		}
		if (!project.getAiRecords().contains(record) && project.getAiRecords().size() >= 100) {
			message.setText(AiText.str("limit"));
			return false;
		}
		if (draftNotes.stream().anyMatch(value -> value.length() > 4000)) {
			message.setText(AiText.str("review_note_limit"));
			return false;
		}
		for (int i = 0; i < findings.size(); i++) {
			findings.get(i).review(draftReviews.get(i), draftNotes.get(i));
		}
		project.addAiRecord(record);
		onSaved.run();
		updateSummary();
		selectFinding();
		message.setText(AiText.str("review_saved"));
		return true;
	}

	private void closeReview() {
		if (canClose()) {
			dispose();
		}
	}

	private boolean canClose() {
		keepDraft();
		boolean dirty = false;
		for (int i = 0; i < findings.size(); i++) {
			AiFinding finding = findings.get(i);
			dirty |= finding.getReview() != draftReviews.get(i) || !finding.getReviewNote().equals(draftNotes.get(i));
		}
		if (!dirty) {
			return true;
		}
		int choice = JOptionPane.showConfirmDialog(this, AiText.str("review_unsaved"), AiText.str("human_review"),
				JOptionPane.YES_NO_CANCEL_OPTION);
		return choice == JOptionPane.NO_OPTION || (choice == JOptionPane.YES_OPTION && saveReview());
	}

	private void updateSummary() {
		long reviewed = findings.stream().filter(f -> f.getReview() != AiFinding.Review.UNREVIEWED).count();
		summary.setText(String.format(AiText.str("findings_summary"), findings.size(), reviewed, findings.size() - reviewed));
	}

	private void showReasoning(AiFinding finding) {
		reasoning.setText("");
		appendReasoning(kindLabel(finding.getKind()) + " · " + AiText.str("model_claim"), false, 0, 12);
		appendReasoning(finding.getTitle(), true, 5, 18);
		appendReasoning(AiText.str("finding_detail"), true, 1, 6);
		appendReasoning(finding.getDetail(), false, 1, 18);
		appendReasoning(AiText.str("finding_gaps"), true, 1, 6);
		appendReasoning(orMissing(finding.getGaps()), false, 1, 18);
		appendReasoning(AiText.str("finding_verification"), true, 1, 6);
		appendReasoning(orMissing(finding.getVerification()), false, 1, 18);
		reasoning.setCaretPosition(0);
	}

	private void appendReasoning(String text, boolean bold, int larger, int spacing) {
		StyledDocument document = reasoning.getStyledDocument();
		SimpleAttributeSet style = new SimpleAttributeSet();
		StyleConstants.setBold(style, bold);
		StyleConstants.setFontSize(style, reasoning.getFont().getSize() + larger);
		StyleConstants.setSpaceBelow(style, spacing);
		StyleConstants.setLineSpacing(style, 0.15f);
		int offset = document.getLength();
		try {
			document.insertString(offset, text + '\n', style);
			document.setParagraphAttributes(offset, text.length() + 1, style, false);
		} catch (BadLocationException e) {
			throw new IllegalStateException("Cannot render finding", e);
		}
	}

	private static String orMissing(String value) {
		return value == null || value.isBlank() ? AiText.str("finding_unspecified") : value;
	}

	private static String kindLabel(AiFinding.Kind value) {
		return AiText.str("finding_" + value.name().toLowerCase(Locale.ROOT));
	}

	private static String reviewLabel(AiFinding.Review value) {
		return AiText.str("review_" + value.name().toLowerCase(Locale.ROOT));
	}

	private static JTextArea text() {
		JTextArea value = new JTextArea();
		value.setEditable(false);
		value.setLineWrap(true);
		value.setWrapStyleWord(true);
		value.setOpaque(false);
		return value;
	}

	private static JLabel plain(String value) {
		JLabel label = new JLabel(value);
		label.putClientProperty("html.disable", true);
		return label;
	}

}
