package jadx.gui.ai;

import java.util.Locale;
import java.util.ResourceBundle;

import jadx.gui.utils.NLS;

public final class AiText {
	private static final ResourceBundle.Control CONTROL =
			ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

	private AiText() {
	}

	public static String str(String key) {
		Locale locale = NLS.currentLocale() == null ? Locale.ENGLISH : NLS.currentLocale().get();
		return ResourceBundle.getBundle("ai.messages", locale, CONTROL).getString(key);
	}
}
