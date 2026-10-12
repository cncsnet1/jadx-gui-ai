package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/** A static observation, not a verified vulnerability. Raw values never enter project JSON. */
public final class AnalysisFinding {
	public enum Kind {
		PRIVATE_KEY("secret", 0),
		CREDENTIAL("secret", 1),
		TOKEN("secret", 1),
		ENDPOINT("network", 3),
		HTTP_ROUTE("network", 3),
		REQUEST_BUILDER("network", 4),
		EXPORTED("config", 4),
		DEBUGGABLE("config", 2),
		BACKUP("config", 5),
		LOGIN("behavior", 2),
		CRYPTO("behavior", 3),
		DYNAMIC("behavior", 3),
		WEBVIEW("behavior", 4),
		PUBLIC_CONFIG("config", 5);

		private final String group;
		private final int priority;

		Kind(String group, int priority) {
			this.group = group;
			this.priority = priority;
		}

		public String group() {
			return group;
		}

		public int priority() {
			return priority;
		}

		public String key() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		public String text(String part) {
			return AiText.str("analysis.rule." + key() + "." + part);
		}
	}

	private String id;
	private Kind kind;
	private String value;
	private List<AiEvidence> evidence = new ArrayList<>();
	private int occurrences;
	private String reviewKey;
	private transient String rawValue;
	private transient String identity;

	private AnalysisFinding() {
	}

	AnalysisFinding(String id, Kind kind, String raw, AiEvidence source) {
		this.id = id;
		this.kind = kind;
		this.rawValue = raw;
		this.value = kind.group().equals("secret")
				? "[redacted]"
				: AnalysisRedactor.redact(kind == Kind.HTTP_ROUTE ? AnalysisRedactor.safeUrl(raw) : raw);
		this.identity = kind.name() + ':' + AiEvidence.fingerprint(raw);
		this.reviewKey = AiEvidence.fingerprint(identity + '\n' + source.getTitle() + '\n' + source.getFingerprint());
		add(source);
	}

	void add(AiEvidence source) {
		occurrences++;
		if (evidence.size() < 5 && evidence.stream().noneMatch(e -> e.getTitle().equals(source.getTitle()))) {
			evidence.add(source);
		}
	}

	String identity() {
		return identity;
	}

	AnalysisFinding copy() {
		AnalysisFinding copy = new AnalysisFinding();
		copy.id = id;
		copy.kind = kind;
		copy.value = value;
		copy.evidence = new ArrayList<>(evidence);
		copy.occurrences = occurrences;
		copy.rawValue = rawValue;
		copy.identity = identity;
		copy.reviewKey = reviewKey;
		return copy;
	}

	String reviewKey() {
		if (reviewKey == null) {
			return legacyReviewKey();
		}
		return reviewKey;
	}

	String legacyReviewKey() {
		AiEvidence source = evidence.get(0);
		return AiEvidence.fingerprint(kind + "\n" + value + '\n' + source.getTitle() + '\n' + source.getFingerprint());
	}

	boolean hasReviewKey() {
		return reviewKey != null;
	}

	public String getId() {
		return id;
	}

	public Kind getKind() {
		return kind;
	}

	public String getValue() {
		return value;
	}

	public String getRawValue() {
		return rawValue;
	}

	public int getOccurrences() {
		return occurrences;
	}

	public List<AiEvidence> getEvidence() {
		return Collections.unmodifiableList(evidence);
	}

	public String getTitle() {
		return kind.text("title");
	}

	public int priority(AnalysisGoal goal) {
		if ((goal == AnalysisGoal.NETWORK && kind.group().equals("network"))
				|| (goal == AnalysisGoal.SECRETS && kind.group().equals("secret"))
				|| (goal == AnalysisGoal.LOGIN && kind == Kind.LOGIN)
				|| (goal == AnalysisGoal.BEHAVIOR && kind.group().equals("behavior"))) {
			return kind.priority() - 10;
		}
		return kind.priority();
	}

	@Override
	public String toString() {
		return id + " · " + getTitle() + " · " + evidence.get(0).getTitle();
	}
}
