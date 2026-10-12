package jadx.gui.mcp;

import java.util.Locale;
import java.util.ResourceBundle;

import jadx.gui.utils.NLS;

public final class McpText {

	private McpText() {
	}

	public static String str(String key) {
		Locale locale = NLS.currentLocale() == null ? Locale.ENGLISH : NLS.currentLocale().get();
		return ResourceBundle.getBundle("mcp.messages", locale,
				ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)).getString(key);
	}
}
