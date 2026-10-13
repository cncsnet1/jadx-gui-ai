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

	/** Public work plan, not private model reasoning; completion always links retained evidence. */
	public static final class Task {
		private final String title;
		private final String status;
		private final List<String> evidence;

		private Task() {
			title = "";
			status = "pending";
			evidence = List.of();
		}

		Task(String title, String status, List<String> evidence) {
			if (title.isBlank() || title.length() > 200 || !Set.of("pending", "active", "done", "blocked").contains(status)
					|| evidence.size() > 8 || (status.equals("done") && evidence.isEmpty())) {
				throw new IllegalArgumentException("Invalid task or completion without evidence");
			}
			this.title = title;
			this.status = status;
			this.evidence = List.copyOf(evidence);
		}

		public String getTitle() {
			return title;
		}

		public String getStatus() {
			return status;
		}

		public List<String> getEvidence() {
			return Collections.unmodifiableList(evidence);
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
	// Absent in older saved runs: history remains readable, but cannot safely reuse positional F/A/K
	// identifiers.
	private Map<String, String> selectedIdentities;
	private Map<String, Set<String>> ownership = new LinkedHashMap<>();
	private Map<String, JadxNodeRef> nodes = new LinkedHashMap<>();
	private Map<String, Set<String>> nodeOwners = new LinkedHashMap<>();
	private List<Step> steps = new ArrayList<>();
	private List<String> questions = new ArrayList<>();
	private Set<String> completedCalls = new LinkedHashSet<>();
	// Resource names only: restoring a search must not consume evidence slots or imply a source read.
	private List<String> resourceTitles = new ArrayList<>();
	private AiRecord answer;
	private int answeredSteps;
	private int answeredFollowUps;
	private List<Task> tasks = new ArrayList<>();
	private List<String> followUps = new ArrayList<>();
	private String action = "";
	private String feedback = "";
	private boolean compatibility;
	private int rounds;
	private int calls;

	private InvestigationRun() {
	}

	public InvestigationRun(String fingerprint, String profile, AnalysisGoal goal, String question, String focusId) {
		evidence = new EvidenceStore(fingerprint, profile);
		this.goal = goal;
		this.question = question;
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

	public List<Task> getTasks() {
		return Collections.unmodifiableList(tasks);
	}

	public List<String> getFollowUps() {
		return Collections.unmodifiableList(followUps);
	}

	public String getAction() {
		return action;
	}

	public String getFeedback() {
		return feedback;
	}

	public boolean isCompatibility() {
		return compatibility;
	}

	public int getRounds() {
		return rounds;
	}

	public int getCalls() {
		return calls;
	}

	void begin(String followUp) {
		if (followUp.length() > 2000 || (!followUp.isBlank() && followUps.size() >= 12)) {
			throw new IllegalArgumentException(AiText.str("agent.followup_limit"));
		}
		if (!followUp.isBlank()) {
			followUps.add(followUp.trim());
			// A new direction needs a fresh plan; retained observations and prior answer stay available.
			tasks.clear();
		}
		rounds = 0;
		calls = 0;
		feedback = "";
		action = AiText.str("analysis.planning");
		stop(State.RUNNING, "");
	}

	void plan(List<Task> values) {
		if (values.isEmpty() || values.size() > 6) {
			throw new IllegalArgumentException("Plan must contain 1-6 tasks");
		}
		for (Task task : values) {
			for (String id : task.getEvidence()) {
				evidence.get(id);
			}
		}
		tasks = new ArrayList<>(values);
	}

	void progress(int round, int callCount, String current, boolean compat) {
		rounds = round;
		calls = callCount;
		action = current;
		compatibility = compat;
	}

	void feedback(String value) {
		feedback = value;
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

	List<String> getResourceTitles() {
		return resourceTitles == null
				? List.of()
				: resourceTitles.stream()
						.filter(title -> title != null && !title.isEmpty() && title.length() <= 768).distinct().limit(384).toList();
	}

	void rememberResources(List<String> titles) {
		Set<String> retained = new LinkedHashSet<>(getResourceTitles());
		for (String title : titles) {
			if (retained.size() >= 384) {
				break;
			}
			if (title != null && !title.isEmpty() && title.length() <= 768) {
				retained.add(title);
			}
		}
		resourceTitles = new ArrayList<>(retained);
	}

	public AiRecord getAnswer() {
		return answer;
	}

	public int getAnsweredSteps() {
		return answeredSteps;
	}

	public int getAnsweredFollowUps() {
		return answeredFollowUps;
	}

	public void setAnswer(AiRecord value) {
		answer = value;
		answeredSteps = steps.size();
		answeredFollowUps = followUps.size();
	}

	void select(List<AnalysisFinding> findings) {
		selected = findings.stream().map(AnalysisFinding::getId).collect(java.util.stream.Collectors.toList());
		selectedIdentities = new LinkedHashMap<>();
		findings.forEach(finding -> selectedIdentities.put(finding.getId(), finding.reviewKey()));
	}

	boolean matchesSelectedSources(AnalysisReport report) {
		if (selectedIdentities == null || selectedIdentities.size() != selected.size()) {
			return false;
		}
		for (String id : selected) {
			AnalysisFinding current = InvestigationService.find(report, id);
			if (current == null || !current.reviewKey().equals(selectedIdentities.get(id))) {
				return false;
			}
		}
		return true;
	}

	public void stop(State value, String reason) {
		state = value;
		this.reason = reason;
	}

	void questions(List<String> values) {
		questions = values.stream().filter(v -> !v.isBlank()).limit(8)
				.map(v -> jadx.gui.ai.AiCodeChunks.prefix(v, 500))
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
		step.target = target;
		step.summary = summary;
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
		copy.selectedIdentities = selectedIdentities == null ? null : new LinkedHashMap<>(selectedIdentities);
		copy.nodes = new LinkedHashMap<>(getNodes());
		copy.nodeOwners = copySets(nodeOwners);
		copy.ownership = copySets(ownership);
		copy.steps = new ArrayList<>(steps);
		copy.questions = new ArrayList<>(questions);
		copy.completedCalls = new LinkedHashSet<>(completedCalls);
		copy.resourceTitles = new ArrayList<>(getResourceTitles());
		copy.answer = answer;
		copy.answeredSteps = answeredSteps;
		copy.answeredFollowUps = answeredFollowUps;
		copy.tasks = new ArrayList<>(tasks);
		copy.followUps = new ArrayList<>(followUps);
		copy.action = action;
		copy.feedback = feedback;
		copy.compatibility = compatibility;
		copy.rounds = rounds;
		copy.calls = calls;
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
