package jadx.gui.mcp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import jadx.gui.ai.AiCancellation;
import jadx.gui.ui.MainWindow;

public final class McpApproval {

	private McpApproval() {
	}

	public static boolean review(MainWindow owner, String provider, McpConnection connection, String tool,
			JsonObject arguments, AiCancellation cancellation) throws InterruptedException {
		CompletableFuture<Boolean> answer = new CompletableFuture<>();
		AtomicReference<JDialog> dialog = new AtomicReference<>();
		Runnable abort = () -> {
			answer.complete(false);
			SwingUtilities.invokeLater(() -> {
				JDialog current = dialog.get();
				if (current != null) {
					current.dispose();
				}
			});
		};
		cancellation.onCancel(abort);
		SwingUtilities.invokeLater(() -> {
			if (answer.isDone() || cancellation.isCancelled()) {
				return;
			}
			String target = connection.transport.equals("http") ? connection.url
					: connection.command + " " + new GsonBuilder().create().toJson(connection.args);
			JTextArea text = new JTextArea(McpText.str("approve_hint") + "\n\n"
					+ connection.name + " / " + tool + "\n" + target + "\n"
					+ McpText.str("provider") + ": " + provider + "\n\n"
					+ new GsonBuilder().setPrettyPrinting().create().toJson(arguments));
			text.setEditable(false);
			text.setLineWrap(true);
			text.setWrapStyleWord(true);
			text.setCaretPosition(0);
			JPanel panel = new JPanel(new BorderLayout());
			panel.add(new JScrollPane(text));
			panel.setPreferredSize(new Dimension(620, 360));
			String allow = McpText.str("approve");
			String deny = McpText.str("deny");
			JOptionPane pane = new JOptionPane(panel, JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION,
					null, new Object[] { deny, allow }, deny);
			JDialog window = pane.createDialog(owner, McpText.str("approve_title"));
			dialog.set(window);
			if (!answer.isDone() && !cancellation.isCancelled()) {
				window.setVisible(true);
				answer.complete(allow.equals(pane.getValue()));
			}
			window.dispose();
		});
		try {
			while (true) {
				cancellation.check();
				try {
					return answer.get(100, TimeUnit.MILLISECONDS);
				} catch (TimeoutException e) {
					// Keep cancellation responsive while waiting for a human, without a consent timeout.
				} catch (ExecutionException e) {
					return false;
				}
			}
		} finally {
			cancellation.removeListener(abort);
			abort.run();
		}
	}
}
