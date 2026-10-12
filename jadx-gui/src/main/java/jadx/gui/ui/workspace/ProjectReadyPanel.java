package jadx.gui.ui.workspace;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import jadx.gui.ai.AiText;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.dialog.SearchDialog;
import jadx.gui.utils.ui.WorkbenchUi;

/** The editor's empty state makes successful loading and the next action explicit. */
public final class ProjectReadyPanel extends JPanel {

	private final MainWindow window;
	private final JLabel fileName = WorkbenchUi.title("", 5);
	private final JLabel counts = WorkbenchUi.mutedLabel("");
	private final JButton search;

	public ProjectReadyPanel(MainWindow window) {
		super(new GridBagLayout());
		this.window = window;
		setOpaque(false);
		JPanel card = WorkbenchUi.card(24);
		card.setPreferredSize(new Dimension(380, 330));
		JPanel heading = WorkbenchUi.transparent();
		heading.add(WorkbenchUi.accentTitle(AiText.str("studio_project_ready"), 10), BorderLayout.NORTH);
		fileName.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		heading.add(fileName, BorderLayout.CENTER);
		heading.add(counts, BorderLayout.SOUTH);
		card.add(heading, BorderLayout.NORTH);
		JTextArea hint = WorkbenchUi.body(AiText.str("studio_project_ready_body"));
		hint.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		card.add(hint, BorderLayout.CENTER);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		actions.setOpaque(false);
		JButton browse = new JButton(AiText.str("studio_browse_files"));
		WorkbenchUi.primary(browse);
		browse.addActionListener(e -> window.focusWorkspaceExplorer());
		actions.add(browse);
		actions.add(javax.swing.Box.createHorizontalStrut(8));
		search = new JButton(AiText.str("studio_search_classes"));
		search.addActionListener(e -> SearchDialog.search(window, SearchDialog.SearchPreset.CLASS));
		actions.add(search);
		card.add(actions, BorderLayout.SOUTH);
		add(card);
	}

	public void refresh() {
		Path path = window.getProject().getFilePaths().get(0);
		fileName.setText(path.getFileName().toString());
		fileName.setToolTipText(path.toString());
		int classes = window.getWrapper().getClasses().size();
		counts.setText(String.format(AiText.str("studio_project_counts"), classes, window.getWrapper().getResources().size()));
		search.setEnabled(classes > 0);
	}
}
