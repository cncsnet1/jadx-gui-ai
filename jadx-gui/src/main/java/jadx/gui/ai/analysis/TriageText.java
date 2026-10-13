package jadx.gui.ai.analysis;

import java.util.Locale;

/** Presentation of reading priority, explicitly separate from vulnerability severity. */
final class TriageText {
	private TriageText() {
	}

	static String summary(EvidenceTriage.Decision decision) {
		return text("tier", decision.getTier().name()) + " · " + text("origin", decision.getOrigin().name())
				+ " · " + text("signal", decision.getSignal().name());
	}

	static String describe(EvidenceTriage.Decision decision) {
		StringBuilder result = new StringBuilder(summary(decision));
		for (String reason : decision.getReasons()) {
			result.append('\n').append(WorkbenchText.str("triage.reason." + reason));
		}
		return result.append('\n').append(WorkbenchText.str("triage.boundary")).toString();
	}

	private static String text(String category, String value) {
		return WorkbenchText.str("triage." + category + '.' + value.toLowerCase(Locale.ROOT));
	}
}
