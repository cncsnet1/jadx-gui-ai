package jadx.gui.burp;

import java.awt.BorderLayout;
import java.awt.Component;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import jadx.gui.mcp.McpConnection;
import jadx.gui.utils.ui.WorkbenchUi;

/** Direct local Burp connection, with manual settings kept outside the normal connection path. */
public final class BurpSetup {
	public static final String DEFAULT_URL = "http://127.0.0.1:9876";

	private BurpSetup() {
	}

	public static McpConnection choose(Component owner) {
		return choose(owner, null);
	}

	public static McpConnection choose(Component owner, McpConnection existing) {
		JTextField endpoint = new JTextField(existing == null ? DEFAULT_URL : existing.url, 36);
		JPanel form = new JPanel(new BorderLayout(0, 12));
		JTextArea hint = WorkbenchUi.body(BurpText.str("setup_hint"));
		hint.setRows(4);
		hint.setColumns(48);
		form.add(hint, BorderLayout.NORTH);
		JPanel fields = new JPanel(new BorderLayout(0, 6));
		fields.add(new JLabel(BurpText.str("server_url")), BorderLayout.NORTH);
		fields.add(endpoint);
		form.add(fields);
		JButton help = new JButton(BurpText.str("install_help"));
		help.addActionListener(e -> openInstallHelp());
		form.add(help, BorderLayout.SOUTH);
		while (JOptionPane.showConfirmDialog(owner, form, BurpText.str("connect"), JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
			try {
				McpConnection config = directConfiguration(endpoint.getText());
				if (existing != null) {
					config.id = existing.id;
					config.name = existing.name;
				}
				return config;
			} catch (RuntimeException e) {
				JOptionPane.showMessageDialog(owner, BurpText.str("setup_invalid"), BurpText.str("connect"), JOptionPane.ERROR_MESSAGE);
			}
		}
		return null;
	}

	public static void openInstallHelp() {
		String url = "https://portswigger.net/bappstore/9952290f04ed4f628e624d0aa9dccebc";
		try {
			java.awt.Desktop.getDesktop().browse(URI.create(url));
		} catch (Exception e) {
			JTextField link = new JTextField(url);
			link.setEditable(false);
			JOptionPane.showMessageDialog(null, link, BurpText.str("install_help"), JOptionPane.PLAIN_MESSAGE);
		}
	}

	public static McpConnection directConfiguration(String address) {
		URI uri = BurpBridge.origin(address);
		if (!"http".equals(uri.getScheme()) || !List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())) {
			throw new IllegalArgumentException(BurpText.str("setup_invalid"));
		}
		McpConnection config = new McpConnection();
		config.name = "Burp Suite";
		config.transport = "sse";
		config.url = uri.toString();
		config.toolProfile = "burp-readonly";
		config.validate();
		return config;
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
