package jadx.gui.ui.workspace;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;

import jadx.gui.ai.AiText;
import jadx.gui.utils.ui.WorkbenchUi;

/** Keeps project loading and failures visible even when there are no editor tabs. */
final class ProjectLoadPanel extends JPanel {

	private final JLabel title = WorkbenchUi.accentTitle("", 8);
	private final JTextArea detail = WorkbenchUi.body("");
	private final JProgressBar progress = new JProgressBar();
	private final JButton open;

	ProjectLoadPanel(Runnable openFile, Runnable showLogs) {
		super(new GridBagLayout());
		setOpaque(false);
		JPanel card = WorkbenchUi.card(28);
		card.setPreferredSize(new Dimension(500, 230));
		card.add(title, BorderLayout.NORTH);
		detail.setRows(3);
		card.add(detail, BorderLayout.CENTER);
		JPanel bottom = WorkbenchUi.transparent();
		progress.setIndeterminate(true);
		bottom.add(progress, BorderLayout.NORTH);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		actions.setOpaque(false);
		actions.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		open = new JButton(AiText.str("studio_open"));
		open.addActionListener(e -> openFile.run());
		actions.add(open);
		JButton logs = new JButton(AiText.str("studio_logs"));
		logs.addActionListener(e -> showLogs.run());
		actions.add(logs);
		bottom.add(actions, BorderLayout.SOUTH);
		card.add(bottom, BorderLayout.SOUTH);
		add(card);
	}

	void loading(String fileName) {
		title.setText(AiText.str("studio_loading"));
		detail.setText(fileName + "\n\n" + AiText.str("studio_loading_hint"));
		progress.setVisible(true);
		progress.setIndeterminate(true);
		open.setVisible(false);
	}

	void failed(String message) {
		title.setText(AiText.str("studio_load_failed"));
		detail.setText(message);
		progress.setIndeterminate(false);
		progress.setVisible(false);
		open.setVisible(true);
	}

	void finished() {
		progress.setIndeterminate(false);
	}
}
