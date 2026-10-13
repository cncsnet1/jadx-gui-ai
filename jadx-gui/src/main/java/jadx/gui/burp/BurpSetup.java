package jadx.gui.burp;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

import jadx.gui.mcp.McpConnection;
import jadx.gui.utils.ui.WorkbenchUi;

/** Configures the user-supplied official stdio proxy; installs and downloads nothing. */
public final class BurpSetup {
	private BurpSetup() {
	}

	public static McpConnection choose(Component owner) {
		JTextField java = new JTextField(Path.of(System.getProperty("java.home"), "bin",
				System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), 36);
		JTextField proxy = new JTextField(36);
		JTextField endpoint = new JTextField("http://127.0.0.1:9876", 36);
		JPanel form = new JPanel(new BorderLayout(0, 12));
		JTextArea hint = WorkbenchUi.body(BurpText.str("setup_hint"));
		hint.setRows(4);
		hint.setColumns(48);
		form.add(hint, BorderLayout.NORTH);
		JPanel fields = new JPanel(new GridLayout(0, 1, 0, 4));
		fields.add(new JLabel(BurpText.str("proxy_jar")));
		JPanel pick = new JPanel(new BorderLayout(6, 0));
		pick.add(proxy);
		JButton browse = new JButton(BurpText.str("browse"));
		browse.addActionListener(e -> {
			JFileChooser chooser = new JFileChooser();
			chooser.setFileFilter(new FileNameExtensionFilter("JAR", "jar"));
			if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
				proxy.setText(chooser.getSelectedFile().getAbsolutePath());
			}
		});
		pick.add(browse, BorderLayout.EAST);
		fields.add(pick);
		fields.add(new JLabel(BurpText.str("server_url")));
		fields.add(endpoint);
		fields.add(new JLabel(BurpText.str("java")));
		fields.add(java);
		form.add(fields);
		form.add(WorkbenchUi.body("https://github.com/PortSwigger/mcp-server"), BorderLayout.SOUTH);
		while (JOptionPane.showConfirmDialog(owner, form, BurpText.str("connect"), JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
			try {
				return configuration(java.getText(), proxy.getText(), endpoint.getText());
			} catch (RuntimeException e) {
				JOptionPane.showMessageDialog(owner, BurpText.str("setup_invalid"), BurpText.str("connect"), JOptionPane.ERROR_MESSAGE);
			}
		}
		return null;
	}

	public static McpConnection configuration(String java, String jar, String endpoint) {
		URI uri = URI.create(endpoint.trim());
		if (!"http".equals(uri.getScheme()) || uri.getHost() == null
				|| !List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()) || uri.getPort() == 0 || uri.getPort() > 65535
				|| uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
				|| !Files.isRegularFile(Path.of(java.trim())) || !Files.isRegularFile(Path.of(jar.trim()))
				|| !jar.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			throw new IllegalArgumentException("Invalid local Burp proxy configuration");
		}
		McpConnection config = new McpConnection();
		config.name = "Burp Suite";
		config.command = java.trim();
		config.args = List.of("-jar", jar.trim(), "--sse-url", uri.toString());
		config.toolProfile = "burp-readonly";
		config.validate();
		return config;
	}
}
