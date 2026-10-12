package jadx.gui.utils.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;

/** Shared, theme-aware surfaces and typography for the workbench. */
public final class WorkbenchUi {
	private WorkbenchUi() {
	}

	public static Color accent() {
		return FlatLaf.isLafDark() ? new Color(0x5ED1BA) : new Color(0x087F70);
	}

	public static Color canvas() {
		return FlatLaf.isLafDark() ? new Color(0x11191E) : new Color(0xEDF2F4);
	}

	public static Color surface() {
		return FlatLaf.isLafDark() ? new Color(0x19242B) : Color.WHITE;
	}

	public static Color line() {
		return FlatLaf.isLafDark() ? new Color(0x2A3A43) : new Color(0xD6E1E5);
	}

	public static Color tint() {
		return FlatLaf.isLafDark() ? new Color(0x203F3B) : new Color(0xDDF3EC);
	}

	public static JTextArea body(String text) {
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setFont(UIManager.getFont("Label.font"));
		return area;
	}

	public static Color muted() {
		Color color = UIManager.getColor("Label.disabledForeground");
		return color == null ? Color.GRAY : color;
	}

	public static JLabel title(String text, float extraSize) {
		JLabel label = new JLabel(text);
		label.putClientProperty("html.disable", true);
		label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + extraSize));
		return label;
	}

	public static JLabel accentTitle(String text, float extraSize) {
		JLabel label = new JLabel(text) {
			@Override
			public void updateUI() {
				super.updateUI();
				setForeground(accent());
			}
		};
		label.putClientProperty("html.disable", true);
		label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + extraSize));
		return label;
	}

	public static JLabel mutedLabel(String text) {
		JLabel label = new JLabel(text);
		label.putClientProperty("html.disable", true);
		label.putClientProperty("FlatLaf.style", "foreground: $Label.disabledForeground");
		return label;
	}

	public static void primary(JButton button) {
		button.putClientProperty("FlatLaf.style", "background: #087f70; foreground: #ffffff; "
				+ "focusedBackground: #087f70; hoverBackground: #096e62; pressedBackground: #075c52; borderWidth: 0");
	}

	public static JPanel transparent() {
		JPanel panel = new JPanel(new BorderLayout(8, 8));
		panel.setOpaque(false);
		return panel;
	}

	public static JPanel card(int padding) {
		JPanel panel = new JPanel(new BorderLayout(12, 12)) {
			@Override
			protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(UIManager.getColor("TextField.background"));
				g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
				Color border = UIManager.getColor("Component.borderColor");
				g.setColor(border == null ? muted() : border);
				g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
				g.dispose();
			}
		};
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
		return panel;
	}
}
