package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jadx.gui.ai.AiRecord;
import jadx.gui.ai.AiReport;
import jadx.gui.ai.AiText;

/** Last bounded project analysis. No live decompiler objects are retained. */
public final class AnalysisReport {
	public static final String RULE_VERSION = "6";
	private String fingerprint;
	private String ruleVersion = RULE_VERSION;
	private String engineProfile = "";
	private String appPackage = "";
	private long createdAt = System.currentTimeMillis();
	private List<AnalysisFinding> findings = new ArrayList<>();
	private List<String> gaps = new ArrayList<>();
	private Map<String, Integer> budgetOmissions = new LinkedHashMap<>();
	private int classes;
	private int totalClasses;
	private int resources;
	private int totalResources;
	private boolean complete;
	private AiRecord analysis;
	private List<ApiEndpoint> apis = new ArrayList<>();
	private Map<String, AssessmentReview> reviews = new LinkedHashMap<>();
	private List<InvestigationRun> investigations = new ArrayList<>();
	private Map<String, ApiDossier> apiDossiers = new LinkedHashMap<>();
	private List<ApiTrafficEvidence> trafficEvidence = new ArrayList<>();
	private ApkProfile profile = new ApkProfile();
	private FrameworkAnalysis frameworkAnalysis = new FrameworkAnalysis();
	private transient java.util.Map<String, ApiEndpoint> apiIndex;
	private transient java.util.Map<ApiEndpoint.Origin, Integer> apiCounts;

	public List<ApiEndpoint> getApis() {
		return Collections.unmodifiableList(apis);
	}

	public String getAppPackage() {
		return appPackage == null ? "" : appPackage;
	}

	public void setAppPackage(String value) {
		if (value != null && value.length() <= 512 && jadx.core.deobf.NameMapper.isValidFullIdentifier(value)) {
			appPackage = value;
		}
	}

	public ApkProfile getProfile() {
		return profile == null ? new ApkProfile() : profile;
	}

	public void setProfile(ApkProfile value) {
		profile = java.util.Objects.requireNonNull(value).snapshot();
	}

	public FrameworkAnalysis getFrameworkAnalysis() {
		return frameworkAnalysis == null ? new FrameworkAnalysis() : frameworkAnalysis;
	}

	public void setFrameworkAnalysis(FrameworkAnalysis value) {
		frameworkAnalysis = java.util.Objects.requireNonNull(value).snapshot();
	}

	public List<InvestigationRun> getInvestigations() {
		return Collections.unmodifiableList(investigations);
	}

	public void putInvestigation(InvestigationRun run) {
		if (!java.util.Objects.equals(fingerprint, run.getEvidence().getSubject())) {
			throw new IllegalArgumentException(AiText.str("assessment.stale"));
		}
		for (int i = 0; i < investigations.size(); i++) {
			if (investigations.get(i).getId().equals(run.getId())) {
				investigations.set(i, run.snapshot());
				return;
			}
		}
		if (investigations.size() >= 24) {
			throw new IllegalStateException(AiText.str("investigation.history_limit"));
		}
		investigations.add(run.snapshot());
	}

	public ApiDossier getApiDossier(String id) {
		return apiDossiers.get(id);
	}

	public void putApiDossier(ApiDossier dossier) {
		if (apis.stream().noneMatch(e -> e.getId().equals(dossier.getEndpointId()))) {
			throw new IllegalArgumentException(AiText.str("assessment.stale"));
		}
		apiDossiers.put(dossier.getEndpointId(), dossier);
	}

	public boolean hasEvidence() {
		return !findings.isEmpty() || !apis.isEmpty() || ProfileLead.hasEvidence(getProfile())
				|| !getFrameworkAnalysis().getFindings().isEmpty();
	}

	public List<ApiTrafficEvidence> getTrafficEvidence() {
		return Collections.unmodifiableList(trafficEvidence);
	}

	public void putTrafficEvidence(ApiEndpoint endpoint, ApiTrafficEvidence evidence) {
		if (!complete || !evidence.belongsTo(endpoint) || apis.stream().noneMatch(a -> a == endpoint)) {
			throw new IllegalArgumentException(AiText.str("assessment.stale"));
		}
		for (int i = 0; i < trafficEvidence.size(); i++) {
			if (trafficEvidence.get(i).identity().equals(evidence.identity())) {
				trafficEvidence.set(i, evidence);
				return;
			}
		}
		if (trafficEvidence.size() >= 200 || trafficEvidence.stream().filter(e -> e.belongsTo(endpoint)).count() >= 20) {
			throw new IllegalStateException(jadx.gui.burp.BurpText.str("evidence_limit"));
		}
		trafficEvidence.add(evidence);
	}

	public void clearTrafficEvidence(ApiEndpoint endpoint) {
		if (complete && apis.stream().anyMatch(a -> a == endpoint)) {
			trafficEvidence.removeIf(e -> e.belongsTo(endpoint));
		}
	}

	public List<Map<String, Object>> trafficEvidenceExport() {
		List<Map<String, Object>> result = new ArrayList<>();
		Map<String, ApiEndpoint> byKey = new LinkedHashMap<>();
		apis.forEach(api -> byKey.putIfAbsent(ApiTrafficEvidence.key(api), api));
		for (ApiTrafficEvidence evidence : trafficEvidence) {
			ApiEndpoint api = byKey.get(evidence.endpointKey());
			if (api != null) {
				result.add(Map.of("endpointId", api.getId(), "evidence", evidence));
			}
		}
		return result;
	}

	public void carryTrafficEvidence(AnalysisReport previous) {
		if (previous != null && fingerprint != null && !fingerprint.isEmpty() && fingerprint.equals(previous.fingerprint)
				&& java.util.Objects.equals(engineProfile, previous.engineProfile)) {
			java.util.Set<String> keys = apis.stream().map(ApiTrafficEvidence::key).collect(java.util.stream.Collectors.toSet());
			trafficEvidence = previous.trafficEvidence.stream().filter(e -> keys.contains(e.endpointKey()))
					.limit(200).collect(java.util.stream.Collectors.toList());
		}
	}

	public AssessmentReview review(String key) {
		return reviews.getOrDefault(key, AssessmentReview.unreviewed());
	}

	public void review(AssessmentItem item, AssessmentReview value) {
		if (!complete || AssessmentItem.from(this).stream().noneMatch(current -> current.key().equals(item.key()))) {
			throw new IllegalArgumentException(AiText.str("assessment.stale"));
		}
		reviews.put(item.key(), java.util.Objects.requireNonNull(value));
	}

	/** Preserve manual work only for the same input bytes and surviving, identical observations. */
	public void carryReviews(AnalysisReport previous) {
		if (previous == null || previous.reviews.isEmpty() || fingerprint == null || fingerprint.isEmpty()
				|| !fingerprint.equals(previous.fingerprint)) {
			return;
		}
		for (AssessmentItem item : AssessmentItem.from(this)) {
			AssessmentReview review = previous.reviews.get(item.key());
			if (review != null) {
				reviews.put(item.key(), review);
			}
		}
		// Reports saved before stable keys were added can only be matched if both sides are unambiguous.
		if (java.util.Objects.equals(ruleVersion, previous.ruleVersion)) {
			Map<String, List<AnalysisFinding>> oldKeys = previous.findings.stream()
					.collect(java.util.stream.Collectors.groupingBy(AnalysisFinding::legacyReviewKey));
			Map<String, List<AnalysisFinding>> newKeys = findings.stream()
					.collect(java.util.stream.Collectors.groupingBy(AnalysisFinding::legacyReviewKey));
			for (Map.Entry<String, List<AnalysisFinding>> entry : oldKeys.entrySet()) {
				List<AnalysisFinding> current = newKeys.getOrDefault(entry.getKey(), List.of());
				if (entry.getValue().size() == 1 && current.size() == 1 && !entry.getValue().get(0).hasReviewKey()) {
					AssessmentReview review = previous.reviews.get("F:" + entry.getKey());
					if (review != null) {
						reviews.putIfAbsent("F:" + current.get(0).reviewKey(), review);
					}
				}
			}
		}
	}

	public void addApi(ApiEndpoint endpoint) {
		if (endpoint == null) {
			return;
		}
		if (apiIndex == null) {
			apiIndex = new java.util.HashMap<>();
			apiCounts = new java.util.EnumMap<>(ApiEndpoint.Origin.class);
			for (ApiEndpoint existing : apis) {
				apiIndex.put(existing.identity(), existing);
				apiCounts.merge(existing.getOrigin(), 1, Integer::sum);
			}
		}
		String key = endpoint.identity();
		ApiEndpoint existing = apiIndex.get(key);
		if (existing != null) {
			existing.merge(endpoint);
			return;
		}
		boolean codeReserve = endpoint.getSources().stream().anyMatch(source -> source.getNode() != null
				&& EvidenceTriage.classRank(this, source.getNode().getDeclaringClass()) >= 0);
		int limit = endpoint.getOrigin() == ApiEndpoint.Origin.CODE_CONTEXT
				? 250 + (codeReserve ? 75 : 0)
				: 750 + (codeReserve ? 150 : 0);
		if (apiCounts.getOrDefault(endpoint.getOrigin(), 0) >= limit) {
			budgetOmission("api." + (codeReserve ? "code_reserve." : "general.")
					+ endpoint.getOrigin().name().toLowerCase(java.util.Locale.ROOT));
			gap(AiText.str("api.limit") + endpoint.getOrigin().label());
			return;
		}
		endpoint.id("A" + (apis.size() + 1));
		apis.add(endpoint);
		apiIndex.put(key, endpoint);
		apiCounts.merge(endpoint.getOrigin(), 1, Integer::sum);
	}

	public AnalysisReport() {
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public void setEngineProfile(String value) {
		engineProfile = value;
	}

	public String getEngineProfile() {
		return engineProfile;
	}

	public boolean matches(String value, String profile) {
		return matches(value) && profile.equals(engineProfile);
	}

	public void setFingerprint(String value) {
		fingerprint = value;
	}

	public boolean matches(String value) {
		return complete && RULE_VERSION.equals(ruleVersion) && value != null && !value.isEmpty() && value.equals(fingerprint);
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public List<AnalysisFinding> getFindings() {
		return Collections.unmodifiableList(findings);
	}

	public List<String> getGaps() {
		return Collections.unmodifiableList(gaps);
	}

	public boolean isComplete() {
		return complete;
	}

	public void setComplete(boolean value) {
		complete = value;
	}

	public void setAnalysis(AiRecord value) {
		analysis = value;
	}

	public AiRecord getAnalysis() {
		return analysis;
	}

	public void add(AnalysisFinding value) {
		findings.add(value);
	}

	public void gap(String value) {
		if (gaps.size() < 50 && !gaps.contains(value)) {
			gaps.add(value);
		}
	}

	/** Counts rejected matches, including repetitions; it is not a count of unique missing findings. */
	public Map<String, Integer> getBudgetOmissions() {
		return budgetOmissions == null ? Map.of() : Collections.unmodifiableMap(budgetOmissions);
	}

	public long getBudgetOmittedCount() {
		return getBudgetOmissions().values().stream().mapToLong(Integer::longValue).sum();
	}

	void budgetOmission(String bucket) {
		if (budgetOmissions == null) {
			budgetOmissions = new LinkedHashMap<>();
		}
		if (budgetOmissions.containsKey(bucket) || budgetOmissions.size() < 64) {
			budgetOmissions.merge(bucket, 1, (previous, increment) -> previous == Integer.MAX_VALUE ? previous : previous + increment);
		}
	}

	public void totals(int code, int res) {
		totalClasses = code;
		totalResources = res;
	}

	public void classRead() {
		classes++;
	}

	public void resourceRead() {
		resources++;
	}

	public String coverage() {
		return String.format(AiText.str("analysis.coverage"), classes, totalClasses, resources, totalResources, findings.size())
				+ " · " + String.format(AiText.str("api.coverage"), apis.size())
				+ (getBudgetOmittedCount() == 0
						? ""
						: " · " + String.format(AiText.str("analysis.budget_omitted"), getBudgetOmittedCount()));
	}

	/** Publish an independent snapshot while the worker continues. */
	public AnalysisReport snapshot() {
		AnalysisReport copy = new AnalysisReport();
		copy.engineProfile = engineProfile;
		copy.appPackage = getAppPackage();
		copy.fingerprint = fingerprint;
		copy.ruleVersion = ruleVersion;
		copy.createdAt = createdAt;
		copy.findings = findings.stream().map(AnalysisFinding::copy).collect(java.util.stream.Collectors.toList());
		copy.gaps = new ArrayList<>(gaps);
		copy.budgetOmissions = new LinkedHashMap<>(getBudgetOmissions());
		copy.classes = classes;
		copy.totalClasses = totalClasses;
		copy.resources = resources;
		copy.totalResources = totalResources;
		copy.complete = complete;
		copy.analysis = analysis;
		copy.apis = apis.stream().map(ApiEndpoint::copy).collect(java.util.stream.Collectors.toList());
		// Review objects are immutable; the mutable index must be independent.
		copy.reviews = new LinkedHashMap<>(reviews);
		copy.investigations = investigations.stream().map(InvestigationRun::snapshot).collect(java.util.stream.Collectors.toList());
		copy.apiDossiers = new LinkedHashMap<>(apiDossiers);
		copy.trafficEvidence = new ArrayList<>(trafficEvidence);
		copy.profile = getProfile().snapshot();
		copy.frameworkAnalysis = getFrameworkAnalysis().snapshot();
		return copy;
	}

	public String markdown() {
		StringBuilder text = new StringBuilder("# " + AiText.str("analysis.title") + "\n\n");
		text.append(coverage()).append("\n\n").append(AiText.str(complete ? "analysis.scan_complete" : "analysis.partial"))
				.append("\n\n").append(AiText.str("analysis.limits")).append("\n\n");
		text.append(getProfile().markdown());
		text.append(getFrameworkAnalysis().markdown());
		for (String gap : gaps) {
			text.append("- ").append(gap).append('\n');
		}
		text.append("\n## ").append(AiText.str("assessment.queue")).append("\n\n")
				.append(AiText.str("assessment.boundary")).append("\n\n");
		for (AssessmentItem item : AssessmentItem.from(this)) {
			AssessmentReview review = review(item.key());
			text.append("\n### ").append(item.finding().getId()).append(" · ")
					.append(item.finding().getTitle()).append("\n\n````text\n")
					.append(AiText.str("assessment.priority")).append(": ").append(item.priority()).append('\n')
					.append(AiText.str("assessment.state")).append(": ").append(review.getStatus()).append('\n')
					.append(AiText.str("assessment.severity")).append(": ").append(review.getSeverity()).append('\n')
					.append(AiText.str("assessment.location")).append(": ").append(item.source().getTitle().replace("````", "` ` ` `"))
					.append('\n').append(AiText.str("analysis.next")).append(": ").append(item.finding().getKind().text("next"));
			if (review.getUpdatedAt() != 0) {
				text.append('\n').append(AiText.str("assessment.updated")).append(": ")
						.append(java.time.Instant.ofEpochMilli(review.getUpdatedAt()))
						.append('\n').append(AiText.str("assessment.note")).append(":\n")
						.append(review.getNote().replace("````", "` ` ` `"));
			}
			text.append("\n````\n");
		}
		if (!apis.isEmpty()) {
			text.append("\n## ").append(AiText.str("api.title")).append("\n\n").append(AiText.str("api.boundary")).append("\n");
			for (ApiEndpoint endpoint : apis) {
				text.append("\n### ").append(endpoint.getId()).append("\n\n````text\n")
						.append(endpoint.detail().replace("````", "` ` ` `")).append("\n````\n");
				ApiDossier dossier = apiDossiers.get(endpoint.getId());
				if (dossier != null) {
					text.append("\n````text\n").append(dossier.describe().replace("````", "` ` ` `")).append("\n````\n");
					dossier.getEvidence().forEach(e -> text.append("\n````text\n").append(e.getTitle().replace("````", "` ` ` `"))
							.append('\n').append(e.getCode().replace("````", "` ` ` `")).append("\n````\n"));
				}
			}
		}
		if (!trafficEvidence.isEmpty()) {
			text.append("\n## ").append(jadx.gui.burp.BurpText.str("saved_evidence")).append("\n\n");
			for (ApiTrafficEvidence evidence : trafficEvidence) {
				text.append("````text\n").append(evidence.describe().replace("````", "` ` ` `")).append("\n````\n\n");
			}
		}
		for (AnalysisFinding finding : findings) {
			text.append("\n## ").append(finding.getId()).append(" · ").append(finding.getTitle()).append("\n\n")
					.append(finding.getKind().text("why")).append("\n\n")
					.append(AiText.str("analysis.unknown")).append(": ").append(finding.getKind().text("gap")).append("\n\n");
			finding.getEvidence().forEach(e -> text.append("\n### ").append(e.getTitle().replace('\n', ' ')).append("\n\n")
					.append("````text\n").append(e.getCode().replace("````", "` ` ` `")).append("\n````\n"));
		}
		if (analysis != null) {
			text.append('\n').append(AiReport.markdown(analysis));
		}
		for (InvestigationRun run : investigations) {
			text.append("\n## ").append(AiText.str("investigation.title")).append("\n\n````text\n")
					.append(run.toString().replace("````", "` ` ` `")).append('\n').append(run.getState()).append(" · ")
					.append(run.getReason().replace("````", "` ` ` `")).append('\n');
			if (run.getAnswer() != null && run.getAnsweredFollowUps() < run.getFollowUps().size()) {
				text.append(AiText.str("agent.answer_previous")).append('\n');
			}
			for (String direction : run.getFollowUps()) {
				text.append(AiText.str("agent.direction")).append(direction.replace("````", "` ` ` `")).append('\n');
			}
			for (InvestigationRun.Task task : run.getTasks()) {
				text.append('[').append(AiText.str("agent.task." + task.getStatus())).append("] ")
						.append(task.getTitle().replace("````", "` ` ` `")).append(" · ").append(task.getEvidence()).append('\n');
			}
			for (String question : run.getQuestions()) {
				text.append("? ").append(question.replace("````", "` ` ` `")).append('\n');
			}
			for (InvestigationRun.Step step : run.getSteps()) {
				text.append(step.getOperation()).append(" · ").append(step.getTarget().replace("````", "` ` ` `")).append('\n')
						.append(step.getSummary().replace("````", "` ` ` `")).append('\n');
			}
			text.append("````\n");
			for (EvidenceStore.Entry entry : run.getEvidence().entries()) {
				text.append("\n### ").append(entry.getId()).append("\n\n````text\n")
						.append(entry.getSource().getTitle().replace("````", "` ` ` `")).append('\n')
						.append(entry.getSource().getCode().replace("````", "` ` ` `")).append("\n````\n");
			}
			if (run.getAnswer() != null && run.getAnswer() != analysis) {
				text.append(AiReport.markdown(run.getAnswer()));
			}
		}
		return text.toString();
	}
}
