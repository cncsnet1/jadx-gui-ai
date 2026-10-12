package jadx.gui.ai.analysis;

import java.util.Locale;

import jadx.gui.ai.AiText;

/** A suggested investigation route from one observed source, never a proven exploit chain. */
public final class ExplorationPath {
	public enum Direction {
		API, ENTRY, IDENTITY, MATERIAL, EXECUTION, CONFIG;

		@Override
		public String toString() {
			return AiText.str("explore.direction." + name().toLowerCase(Locale.ROOT));
		}
	}

	private ExplorationPath() {
	}

	public static Direction direction(AssessmentItem item) {
		switch (item.finding().getKind()) {
			case HTTP_ROUTE:
			case ENDPOINT:
			case REQUEST_BUILDER:
				return Direction.API;
			case EXPORTED:
			case WEBVIEW:
				return Direction.ENTRY;
			case LOGIN:
				return Direction.IDENTITY;
			case PRIVATE_KEY:
			case CREDENTIAL:
			case TOKEN:
			case CRYPTO:
				return Direction.MATERIAL;
			case DYNAMIC:
				return Direction.EXECUTION;
			default:
				return Direction.CONFIG;
		}
	}

	public static String next(AssessmentItem item) {
		return item.finding().getKind().text("route_target");
	}

	public static String detail(AssessmentItem item) {
		AnalysisFinding.Kind kind = item.finding().getKind();
		return item.title() + "\n" + direction(item) + "\n\n"
				+ AiText.str("explore.start") + "\n" + kind.text("why") + "\n" + item.source().getTitle() + "\n\n"
				+ AiText.str("explore.route") + "\n" + kind.text("route") + "\n\n"
				+ AiText.str("explore.boundary") + "\n\n"
				+ AiText.str("explore.next") + "\n" + kind.text("next") + "\n\n"
				+ AiText.str("explore.handoff") + "\n" + kind.text("handoff") + "\n\n"
				+ AiText.str("analysis.unknown") + "\n" + kind.text("gap") + "\n\n"
				+ AiText.str("assessment.evidence_title") + "\n" + item.evidenceText();
	}

	public static String markdown(AssessmentItem item, AnalysisReport report) {
		return "# " + AiText.str("explore.package") + "\n\n"
				+ AiText.str(report.isComplete() ? "analysis.scan_complete" : "analysis.partial") + "\n\n"
				+ report.coverage() + "\n\n" + AiText.str("explore.package_hint")
				+ "\n\n````text\n" + detail(item).replace("````", "` ` ` `") + "\n````\n\n"
				+ AiText.str("analysis.limits") + "\n\n" + String.join("\n\n", report.getGaps());
	}
}
