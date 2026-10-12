package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;

/** Derived triage view; investigation priority never implies vulnerability severity. */
public final class AssessmentItem {
	public enum Priority {
		FIRST, FOLLOW_UP, INVENTORY;

		@Override
		public String toString() {
			return AiText.str("assessment.priority." + name().toLowerCase(Locale.ROOT));
		}
	}

	private final AnalysisFinding finding;
	private final ApiEndpoint api;
	private final String key;

	private AssessmentItem(AnalysisFinding finding, ApiEndpoint api) {
		this.finding = finding;
		this.api = api;
		key = api == null
				? "F:" + finding.reviewKey()
				: "A:" + AiEvidence.fingerprint(api.identity() + '\n' + api.getBody() + '\n'
						+ String.join("\n", api.getAuthHints()) + '\n' + api.isDynamicUrl());
	}

	public static List<AssessmentItem> from(AnalysisReport report) {
		List<AssessmentItem> result = new ArrayList<>();
		if (report == null) {
			return result;
		}
		for (AnalysisFinding finding : report.getFindings()) {
			// Address strings are also represented, with richer metadata, in the API inventory.
			if (finding.getKind() == Kind.ENDPOINT && !report.getApis().isEmpty()) {
				continue;
			}
			result.add(new AssessmentItem(finding, null));
		}
		for (ApiEndpoint api : report.getApis()) {
			result.add(new AssessmentItem(api.asFinding(), api));
		}
		result.sort(Comparator.comparing(AssessmentItem::priority).thenComparingInt(i -> i.finding.priority(AnalysisGoal.SECURITY)));
		return result;
	}

	public String key() {
		return key;
	}

	public AnalysisFinding finding() {
		return finding;
	}

	public String group() {
		return finding.getKind().group();
	}

	public String title() {
		if (api == null) {
			return finding.getId() + " · " + finding.getTitle();
		}
		return api.getId() + " · " + (api.getMethod().isEmpty() ? "" : api.getMethod() + " ")
				+ (api.getAddress().isEmpty() ? finding.getTitle() : api.getAddress());
	}

	public AiEvidence source() {
		return api == null ? finding.getEvidence().get(0) : api.getSources().get(0);
	}

	public Priority priority() {
		Kind kind = finding.getKind();
		if (kind.group().equals("secret") || kind == Kind.DEBUGGABLE) {
			return Priority.FIRST;
		}
		if (kind == Kind.ENDPOINT || kind == Kind.PUBLIC_CONFIG) {
			return Priority.INVENTORY;
		}
		return Priority.FOLLOW_UP;
	}

	public String evidenceType() {
		if (api != null) {
			return api.getOrigin().label();
		}
		return AiText.str("assessment.evidence." + (group().equals("behavior") ? "heuristic" : "static"));
	}

	public String detail() {
		Kind kind = finding.getKind();
		StringBuilder text = new StringBuilder(title()).append("\n\n")
				.append(AiText.str("assessment.observed")).append('\n').append(kind.text("why"))
				.append("\n\n").append(AiText.str("assessment.impact")).append('\n').append(kind.text("impact"))
				.append("\n\n").append(AiText.str("analysis.unknown")).append('\n').append(kind.text("gap"))
				.append("\n\n").append(AiText.str("analysis.next")).append('\n').append(kind.text("next"));
		if (api != null && api.getOrigin() == ApiEndpoint.Origin.DECLARATION) {
			text.append('\n').append(AiText.str("assessment.api_verify"));
		}
		text.append("\n\n").append(AiText.str("assessment.evidence_title")).append('\n');
		return text.append(evidenceText()).toString();
	}

	public String evidenceText() {
		StringBuilder text = new StringBuilder();
		if (api != null) {
			text.append(api.detail());
		} else {
			for (AiEvidence source : finding.getEvidence()) {
				text.append('\n').append(source.getTitle()).append('\n').append(source.getCode()).append('\n');
			}
		}
		return text.toString();
	}
}
