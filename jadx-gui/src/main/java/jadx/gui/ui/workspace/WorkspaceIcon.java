package jadx.gui.ui.workspace;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.Icon;

import jadx.gui.utils.ui.WorkbenchUi;

/** Small native vector vocabulary shared by navigation and command surfaces. */
public final class WorkspaceIcon implements Icon {
	public enum Kind {
		HOME, FILES, SEARCH, AI, SETTINGS, CONSOLE, FOCUS, ARROW, LOGO, MORE
	}

	private final Kind kind;
	private final int size;

	public WorkspaceIcon(Kind kind, int size) {
		this.kind = kind;
		this.size = size;
	}

	@Override
	public int getIconWidth() {
		return size;
	}

	@Override
	public int getIconHeight() {
		return size;
	}

	@Override
	public void paintIcon(Component component, Graphics graphics, int x, int y) {
		Graphics2D g = (Graphics2D) graphics.create();
		g.translate(x, y);
		g.scale(size / 24.0, size / 24.0);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(component.isEnabled() ? component.getForeground() : WorkbenchUi.muted());
		switch (kind) {
			case HOME:
				path(g, 3, 10, 12, 3, 21, 10);
				path(g, 5, 9, 5, 21, 10, 21, 10, 14, 14, 14, 14, 21, 19, 21, 19, 9);
				break;
			case FILES:
				path(g, 3, 7, 3, 4, 10, 4, 13, 7, 21, 7, 21, 20, 3, 20, 3, 7, 21, 7);
				break;
			case SEARCH:
				g.drawOval(3, 3, 12, 12);
				g.drawLine(14, 14, 21, 21);
				break;
			case AI:
				path(g, 12, 2, 14.5, 9, 22, 12, 14.5, 15, 12, 22, 9.5, 15, 2, 12, 9.5, 9, 12, 2);
				break;
			case SETTINGS:
				g.drawOval(5, 5, 14, 14);
				g.drawOval(9, 9, 6, 6);
				for (int i = 0; i < 8; i++) {
					g.drawLine(12, 2, 12, 5);
					g.rotate(Math.PI / 4, 12, 12);
				}
				break;
			case CONSOLE:
				g.drawRoundRect(2, 4, 20, 16, 4, 4);
				path(g, 6, 9, 9, 12, 6, 15);
				g.drawLine(12, 15, 17, 15);
				break;
			case FOCUS:
				path(g, 3, 9, 3, 3, 9, 3);
				path(g, 15, 3, 21, 3, 21, 9);
				path(g, 21, 15, 21, 21, 15, 21);
				path(g, 9, 21, 3, 21, 3, 15);
				break;
			case ARROW:
				path(g, 5, 12, 19, 12, 14, 7);
				path(g, 19, 12, 14, 17);
				break;
			case LOGO:
				g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				path(g, 8, 4, 2, 12, 8, 20);
				path(g, 16, 4, 22, 12, 16, 20);
				path(g, 14, 6, 10, 18);
				break;
			case MORE:
				for (int i = 4; i <= 20; i += 8) {
					g.fillOval(i - 1, 11, 2, 2);
				}
				break;
		}
		g.dispose();
	}

	private static void path(Graphics2D g, double... coordinates) {
		Path2D path = new Path2D.Double();
		path.moveTo(coordinates[0], coordinates[1]);
		for (int i = 2; i < coordinates.length; i += 2) {
			path.lineTo(coordinates[i], coordinates[i + 1]);
		}
		g.draw(path);
	}
}
