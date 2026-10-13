package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiRecord;
import jadx.gui.ai.AiText;

/** A resumable investigation, independent of model chat state and UI selection. */
public final class InvestigationRun {
	static final class BudgetExceededException extends IllegalStateException {
		BudgetExceededException(String message) {
			super(message);
		}
	}

	public enum State {
		RUNNING, COMPLETED, BUDGET, NO_PROGRESS, NEEDS_EVIDENCE, CANCELLED, FAILED;

		@Override
		public String toString() {
			return AiText.str("investigation.state." + name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	public static final class Step {
		private String operation;
		private String target;
		private String summary;
		private List<String> evidence;
		private long createdAt;

		private Step() {
		}

		public String getOperation() {
			return operation;
		}

		public String getTarget() {
			return target;
		}

		public String getSummary() {
			return summary;
		}

		public List<String> getEvidence() {
			return Collections.unmodifiableList(evidence);
		}

		public long getCreatedAt() {
			return createdAt;
		}
	}

	private String id = UUID.randomUUID().toString();
	private long createdAt = System.currentTimeMillis();
	private AnalysisGoal goal;
	private String question;
	private String focusId;
	private State state = State.RUNNING;
	private String reason = "";
	private EvidenceStore evidence;
	private List<String> selected = new ArrayList<>();
	private Map<String, Set<String>> ownership = new LinkedHashMap<>();
	private Map<String, JadxNodeRef> nodes = new LinkedHashMap<>();
	private Map<String, Set<String>> nodeOwners = new LinkedHashMap<>();
	private List<Step> steps = new ArrayList<>();
	private List<String> questions = new ArrayList<>();
	private Set<String> completedCalls = new LinkedHashSet<>();
	private AiRecord answer;
	private int answeredSteps;

	private InvestigationRun() {
	}

	public InvestigationRun(String fingerprint, String profile, AnalysisGoal goal, String question, String focusId) {
		evidence = new EvidenceStore(fingerprint, profile);
		this.goal = goal;
		this.question = AnalysisRedactor.redact(question);
		this.focusId = focusId;
	}

	public String getId() {
		return id;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public AnalysisGoal getGoal() {
		return goal;
	}

	public String getQuestion() {
		return question;
	}

	public String getFocusId() {
		return focusId;
	}

	public State getState() {
		return state;
	}

	public String getReason() {
		return reason;
	}

	public EvidenceStore getEvidence() {
		return evidence;
	}

	public List<String> getSelected() {
		return Collections.unmodifiableList(selected);
	}

	public List<Step> getSteps() {
		return Collections.unmodifiableList(steps);
	}

	public List<String> getQuestions() {
		return Collections.unmodifiableList(questions);
	}

	public Map<String, JadxNodeRef> getNodes() {
		Map<String, JadxNodeRef> copy = new LinkedHashMap<>();
		nodes.forEach((id, ref) -> copy.put(id, new JadxNodeRef(ref.getType(), ref.getDeclaringClass(), ref.getShortId())));
		return Collections.unmodifiableMap(copy);
	}

	public AiRecord getAnswer() {
		return answer;
	}

	public int getAnsweredSteps() {
		return answeredSteps;
	}

	public void setAnswer(AiRecord value) {
		answer = value;
		answeredSteps = steps.size();
	}

	void select(List<AnalysisFinding> findings) {
		selected = findings.stream().map(AnalysisFinding::getId).collect(java.util.stream.Collectors.toList());
	}

	public void stop(State value, String reason) {
		state = value;
		this.reason = AnalysisRedactor.redact(reason);
	}

	void questions(List<String> values) {
		questions = values.stream().filter(v -> !v.isBlank()).limit(8)
				.map(v -> AnalysisRedactor.redact(jadx.gui.ai.AiCodeChunks.prefix(v, 500)))
				.distinct().collect(java.util.stream.Collectors.toList());
	}

	String retain(String operation, AiEvidence source, List<String> parents, boolean code, Set<String> owners) {
		String id = evidence.retain(operation, source, parents, code);
		for (String owner : owners) {
			if (selected.contains(owner)) {
				ownership.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(id);
			}
		}
		register(source.getNode(), owners);
		return id;
	}

	String register(JadxNodeRef ref, Set<String> owners) {
		if (ref == null) {
			return "";
		}
		String id = nodes.entrySet().stream().filter(e -> e.getValue().equals(ref)).map(Map.Entry::getKey).findFirst().orElse(null);
		if (id == null) {
			if (nodes.size() >= 160) {
				return "";
			}
			id = "N" + (nodes.size() + 1);
			nodes.put(id, new JadxNodeRef(ref.getType(), ref.getDeclaringClass(), ref.getShortId()));
		}
		nodeOwners.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).addAll(owners);
		return id;
	}

	Set<String> owners(String node) {
		return Set.copyOf(nodeOwners.getOrDefault(node, Set.of()));
	}

	Set<String> evidenceOwners(String id) {
		Set<String> result = new LinkedHashSet<>();
		ownership.forEach((lead, ids) -> {
			if (ids.contains(id)) {
				result.add(lead);
			}
		});
		return result;
	}

	boolean completed(String key) {
		return completedCalls.contains(key);
	}

	void step(String operation, String target, String summary, List<String> ids, String completedKey) {
		if (steps.size() >= 160) {
			throw new BudgetExceededException("Investigation history budget reached");
		}
		Step step = new Step();
		step.operation = operation;
		step.target = AnalysisRedactor.redact(target);
		step.summary = AnalysisRedactor.redact(summary);
		step.evidence = List.copyOf(ids);
		step.createdAt = System.currentTimeMillis();
		steps.add(step);
		if (completedKey != null) {
			completedCalls.add(completedKey);
		}
	}

	public InvestigationRun snapshot() {
		InvestigationRun copy = new InvestigationRun();
		copy.id = id;
		copy.createdAt = createdAt;
		copy.goal = goal;
		copy.question = question;
		copy.focusId = focusId;
		copy.state = state;
		copy.reason = reason;
		copy.evidence = evidence.snapshot();
		copy.selected = new ArrayList<>(selected);
		copy.nodes = new LinkedHashMap<>(getNodes());
		copy.nodeOwners = copySets(nodeOwners);
		copy.ownership = copySets(ownership);
		copy.steps = new ArrayList<>(steps);
		copy.questions = new ArrayList<>(questions);
		copy.completedCalls = new LinkedHashSet<>(completedCalls);
		copy.answer = answer;
		copy.answeredSteps = answeredSteps;
		return copy;
	}

	private static Map<String, Set<String>> copySets(Map<String, Set<String>> source) {
		Map<String, Set<String>> copy = new LinkedHashMap<>();
		source.forEach((key, values) -> copy.put(key, new LinkedHashSet<>(values)));
		return copy;
	}

	@Override
	public String toString() {
		return goal + " · " + (question.isBlank() ? (focusId == null ? AiText.str("investigation.default_goal") : focusId) : question);
	}
}
