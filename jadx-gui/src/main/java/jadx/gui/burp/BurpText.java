package jadx.gui.burp;

import java.util.Locale;
import java.util.ResourceBundle;

import jadx.gui.utils.NLS;

public final class BurpText {

	private BurpText() {
	}

	public static String str(String key) {
		Locale locale = NLS.currentLocale() == null ? Locale.ENGLISH : NLS.currentLocale().get();
		return ResourceBundle.getBundle("burp.messages", locale,
				ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)).getString(key);
	}
}
