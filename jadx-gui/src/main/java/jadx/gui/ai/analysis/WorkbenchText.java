package jadx.gui.ai.analysis;

import java.util.Locale;
import java.util.ResourceBundle;

import jadx.gui.utils.NLS;

/** Copy for the evidence-first application workbench. */
public final class WorkbenchText {

	private WorkbenchText() {
	}

	public static String str(String key) {
		Locale locale = NLS.currentLocale() == null ? Locale.ENGLISH : NLS.currentLocale().get();
		return ResourceBundle.getBundle("workbench.messages", locale,
				ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)).getString(key);
	}
}
