package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/** A bounded static observation with original values, not a verified vulnerability. */
public final class AnalysisFinding {
	private static final int MAX_SOURCE_REFERENCES = 128;

	/**
	 * Location index is separate from the five full snippet previews and does not claim complete
	 * coverage.
	 */
	public static final class SourceReference {
		private JadxNodeRef node;
		private String title;
		private String fingerprint;
		private boolean truncated;

		private SourceReference() {
		}

		private SourceReference(AiEvidence source) {
			node = source.getNode();
			title = source.getTitle();
			fingerprint = source.getFingerprint();
			truncated = source.isTruncated();
		}

		public JadxNodeRef getNode() {
			return node;
		}

		public String getTitle() {
			return title;
		}

		public String getFingerprint() {
			return fingerprint;
		}

		public boolean isTruncated() {
			return truncated;
		}

		private boolean matches(AiEvidence source) {
			return java.util.Objects.equals(node, source.getNode()) && java.util.Objects.equals(title, source.getTitle())
					&& java.util.Objects.equals(fingerprint, source.getFingerprint());
		}
	}

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
		PUBLIC_CONFIG("config", 5),
		APK_STRUCTURE("structure", 6),
		FRAMEWORK_CODE("framework", 2);

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
	private List<SourceReference> sourceReferences = new ArrayList<>();
	private int omittedSourceReferences;
	private int occurrences;
	private String reviewKey;
	private String rawValue;
	private transient String identity;

	private AnalysisFinding() {
	}

	AnalysisFinding(String id, Kind kind, String raw, AiEvidence source) {
		this.id = id;
		this.kind = kind;
		this.rawValue = ApiEndpoint.boundedText(raw, 8192);
		this.value = rawValue;
		this.identity = kind.name() + ':' + AiEvidence.fingerprint(raw);
		this.reviewKey = AiEvidence.fingerprint(identity + '\n' + source.getTitle() + '\n' + source.getFingerprint());
		add(source);
	}

	void add(AiEvidence source) {
		add(source, null);
	}

	void add(AiEvidence source, AnalysisReport report) {
		if (reviewKey == null && !evidence.isEmpty()) {
			reviewKey = legacyReviewKey();
		}
		occurrences++;
		if (sourceReferences == null) {
			sourceReferences = new ArrayList<>();
		}
		if (sourceReferences.isEmpty() && !evidence.isEmpty()) {
			evidence.forEach(previous -> sourceReferences.add(new SourceReference(previous)));
		}
		if (sourceReferences.stream().noneMatch(reference -> reference.matches(source))) {
			if (sourceReferences.size() < MAX_SOURCE_REFERENCES) {
				sourceReferences.add(new SourceReference(source));
			} else {
				omittedSourceReferences++;
			}
		}
		if (evidence.stream().anyMatch(e -> e.getTitle().equals(source.getTitle()))) {
			return;
		}
		if (evidence.size() < 5 || report != null) {
			evidence.add(source);
			if (report != null) {
				evidence.sort(java.util.Comparator.<AiEvidence>comparingInt(e -> EvidenceTriage.sourceScore(report, e)).reversed());
			}
			if (evidence.size() > 5) {
				evidence.remove(5);
			}
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
		copy.sourceReferences = new ArrayList<>(getSourceReferences());
		copy.omittedSourceReferences = omittedSourceReferences;
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

	public List<SourceReference> getSourceReferences() {
		if (sourceReferences == null || sourceReferences.isEmpty()) {
			return evidence.stream().map(SourceReference::new).collect(java.util.stream.Collectors.toUnmodifiableList());
		}
		return Collections.unmodifiableList(sourceReferences);
	}

	/**
	 * Number of source occurrences not indexed after the cap, not a count of unique omitted locations.
	 */
	public int getOmittedSourceReferences() {
		return omittedSourceReferences;
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
