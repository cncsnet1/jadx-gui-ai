package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;

/** Project-scoped, content-addressed observations. Snapshots contain no live decompiler objects. */
public final class EvidenceStore {
	public static final int MAX_ENTRIES = 96;
	public static final int MAX_CHARACTERS = 384_000;
	private String subject = "";
	private String profile = "";
	private Map<String, Entry> entries = new LinkedHashMap<>();

	public static final class Entry {
		private String id;
		private String operation;
		private AiEvidence source;
		private List<String> parents;
		private boolean behavioralCode;

		private Entry() {
		}

		public String getId() {
			return id;
		}

		public String getOperation() {
			return operation;
		}

		public AiEvidence getSource() {
			return copySource(source);
		}

		public List<String> getParents() {
			return Collections.unmodifiableList(parents);
		}

		public boolean isBehavioralCode() {
			return behavioralCode;
		}
	}

	private EvidenceStore() {
	}

	public EvidenceStore(String subject, String profile) {
		this.subject = subject;
		this.profile = profile;
	}

	public String retain(String operation, AiEvidence source, List<String> parents, boolean behavioralCode) {
		if (!entries.keySet().containsAll(parents)) {
			throw new IllegalArgumentException("Evidence parent is not retained in this investigation");
		}
		String clean = AnalysisRedactor.redact(source.getCode());
		AiEvidence retained = new AiEvidence(source.getNode(), AnalysisRedactor.redact(source.getTitle()), clean,
				source.getFingerprint(), source.isTruncated());
		List<String> links = new ArrayList<>(parents);
		Collections.sort(links);
		JadxNodeRef node = source.getNode();
		Object identityNode = node == null
				? source.getTitle()
				: List.of(node.getType().name(), node.getDeclaringClass(), java.util.Objects.toString(node.getShortId(), ""));
		String identity = new Gson().toJson(List.of(subject, profile, operation,
				identityNode, clean,
				source.getFingerprint(), source.isTruncated(), behavioralCode, links));
		String id = "E-" + AiEvidence.fingerprint(identity);
		if (entries.containsKey(id)) {
			return id;
		}
		if (entries.size() >= MAX_ENTRIES || characters() + clean.length() > MAX_CHARACTERS) {
			throw new InvestigationRun.BudgetExceededException("Evidence budget reached");
		}
		Entry entry = new Entry();
		entry.id = id;
		entry.operation = operation;
		entry.source = copySource(retained);
		entry.parents = List.copyOf(links);
		entry.behavioralCode = behavioralCode;
		entries.put(id, entry);
		return id;
	}

	public Entry get(String id) {
		Entry entry = entries.get(id);
		if (entry == null) {
			throw new IllegalArgumentException("Evidence is not available in this investigation");
		}
		return entry;
	}

	public List<Entry> entries() {
		return List.copyOf(entries.values());
	}

	public String getSubject() {
		return subject;
	}

	public String getProfile() {
		return profile;
	}

	public int characters() {
		return entries.values().stream().mapToInt(e -> e.source.getCode().length()).sum();
	}

	public EvidenceStore snapshot() {
		EvidenceStore copy = new EvidenceStore(subject, profile);
		// Entries are sealed before publication; never mutate them after retain().
		copy.entries = new LinkedHashMap<>(entries);
		return copy;
	}

	private static AiEvidence copySource(AiEvidence source) {
		JadxNodeRef node = source.getNode();
		JadxNodeRef ref = node == null ? null : new JadxNodeRef(node.getType(), node.getDeclaringClass(), node.getShortId());
		return new AiEvidence(ref, source.getTitle(), source.getCode(), source.getFingerprint(), source.isTruncated());
	}
}
