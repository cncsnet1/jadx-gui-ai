package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jadx.gui.ai.AiEvidence;

/** Local, bounded framework observations. No script is executed and no runtime flow is implied. */
public final class FrameworkAnalysis {

	public enum Kind {
		CONFIGURATION, PAGES, BUSINESS_SCRIPTS, REQUESTS, AUTH_STORAGE, INTERCEPTORS, NATIVE_BRIDGE, CRYPTO
	}

	public enum Status {
		FOUND, NO_RESULTS, NOT_COVERED
	}

	public enum RouteStatus {
		COMPLETE, PARTIAL, INVENTORY_ONLY
	}

	public static final class Route {
		private String id;
		private String name;
		private RouteStatus status = RouteStatus.INVENTORY_ONLY;
		private List<AiEvidence> evidence = new ArrayList<>();
		private List<String> gaps = new ArrayList<>();
		private String nextAction;
		private List<Section> sections = new ArrayList<>();
		private List<String> prioritizedResources = new ArrayList<>();

		private Route() {
		}

		Route(String id, String name, List<AiEvidence> evidence, String nextAction) {
			this.id = id;
			this.name = name;
			this.evidence = new ArrayList<>(evidence);
			this.nextAction = nextAction;
			for (Kind kind : Kind.values()) {
				sections.add(new Section(kind));
			}
		}

		public String getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		public RouteStatus getStatus() {
			return status;
		}

		public List<AiEvidence> getEvidence() {
			return Collections.unmodifiableList(evidence);
		}

		public List<String> getGaps() {
			return Collections.unmodifiableList(gaps);
		}

		public String getNextAction() {
			return nextAction;
		}

		public int getResourcesRead() {
			return prioritizedResources.size();
		}

		public List<String> getPrioritizedResources() {
			return Collections.unmodifiableList(prioritizedResources);
		}

		public List<Section> getSections() {
			return Collections.unmodifiableList(sections);
		}

		public List<Finding> getFindings() {
			return sections.stream().flatMap(section -> section.findings.stream()).collect(java.util.stream.Collectors.toList());
		}
	}

	public static final class Finding {
		private String id;
		private Kind kind;
		private String title;
		private String summary;
		private String task;
		private String routeId = "";
		private List<AiEvidence> evidence = new ArrayList<>();

		private Finding() {
		}

		Finding(Kind kind, String title, String summary, String task, AiEvidence source) {
			this.kind = kind;
			this.title = title;
			this.summary = summary;
			this.task = task;
			evidence.add(source);
			id = "U" + AiEvidence.fingerprint(kind + "\n" + source.getTitle() + "\n" + source.getFingerprint()).substring(0, 16);
		}

		public String getId() {
			return id;
		}

		public String getRouteId() {
			return routeId == null ? "" : routeId;
		}

		public Kind getKind() {
			return kind;
		}

		public String getTitle() {
			return title;
		}

		public String getSummary() {
			return summary;
		}

		public String getTask() {
			return task;
		}

		public List<AiEvidence> getEvidence() {
			return Collections.unmodifiableList(evidence);
		}
	}

	public static final class Section {
		private Kind kind;
		private boolean covered;
		private List<Finding> findings = new ArrayList<>();
		private List<String> limitations = new ArrayList<>();

		private Section() {
		}

		Section(Kind kind) {
			this.kind = kind;
		}

		public Kind getKind() {
			return kind;
		}

		public Status getStatus() {
			return findings.isEmpty() ? covered ? Status.NO_RESULTS : Status.NOT_COVERED : Status.FOUND;
		}

		public List<Finding> getFindings() {
			return Collections.unmodifiableList(findings);
		}

		public List<String> getLimitations() {
			return Collections.unmodifiableList(limitations);
		}
	}

	private String framework = "unknown";
	private List<Section> sections = new ArrayList<>();
	private List<String> gaps = new ArrayList<>();
	private List<String> prioritizedResources = new ArrayList<>();
	private int resourcesRead;
	private boolean complete;
	private List<Route> routes = new ArrayList<>();
	private transient Route activeRoute;

	public FrameworkAnalysis() {
		for (Kind kind : Kind.values()) {
			sections.add(new Section(kind));
		}
	}

	public String getFramework() {
		return routes.isEmpty() ? framework : routes.stream().map(Route::getName).collect(java.util.stream.Collectors.joining(" · "));
	}

	public List<Route> getRoutes() {
		return Collections.unmodifiableList(routes);
	}

	void route(String id, String name, List<AiEvidence> evidence, String nextAction) {
		if (routes.stream().noneMatch(route -> route.id.equals(id))) {
			routes.add(new Route(id, name, evidence, nextAction));
		}
	}

	void activate(String id) {
		activeRoute = routes.stream().filter(route -> route.id.equals(id)).findFirst().orElse(null);
	}

	String activeRouteId() {
		return activeRoute == null ? "" : activeRoute.id;
	}

	void routeStatus(RouteStatus value) {
		if (activeRoute != null) {
			activeRoute.status = value;
		}
	}

	void routeRead(String source) {
		if (activeRoute != null && !activeRoute.prioritizedResources.contains(source)) {
			activeRoute.prioritizedResources.add(source);
		}
	}

	void routeEvidence(AiEvidence source) {
		if (activeRoute != null && activeRoute.evidence.size() < 40
				&& activeRoute.evidence.stream().noneMatch(value -> value.getTitle().equals(source.getTitle())
						&& value.getFingerprint().equals(source.getFingerprint()))) {
			activeRoute.evidence.add(source);
		}
	}

	public List<Section> getSections() {
		return Collections.unmodifiableList(sections);
	}

	public List<Finding> getFindings() {
		return sections.stream().flatMap(section -> section.findings.stream()).collect(java.util.stream.Collectors.toList());
	}

	public List<String> getGaps() {
		return Collections.unmodifiableList(gaps);
	}

	/** Archive-qualified names of resources actually read, ordered by business relevance. */
	public List<String> getPrioritizedResources() {
		return Collections.unmodifiableList(prioritizedResources);
	}

	public boolean isComplete() {
		return complete;
	}

	public int getResourcesRead() {
		return resourcesRead;
	}

	void framework(String value) {
		framework = value;
	}

	void complete(boolean value) {
		complete = value;
	}

	void read(String source) {
		resourcesRead++;
		prioritizedResources.add(source);
	}

	void covered(Kind kind) {
		section(kind).covered = true;
		if (activeRoute != null) {
			routeSection(kind).covered = true;
		}
	}

	void gap(String code) {
		if (gaps.size() < 32 && !gaps.contains(code)) {
			gaps.add(code);
		}
		if (activeRoute != null && activeRoute.gaps.size() < 32 && !activeRoute.gaps.contains(code)) {
			activeRoute.gaps.add(code);
		}
	}

	void limit(Kind kind, String code) {
		Section section = section(kind);
		if (!section.limitations.contains(code)) {
			section.limitations.add(code);
		}
		if (activeRoute != null && !routeSection(kind).limitations.contains(code)) {
			routeSection(kind).limitations.add(code);
		}
	}

	boolean add(Finding finding) {
		if (activeRoute != null && finding.getRouteId().isEmpty()) {
			finding.routeId = activeRoute.id;
			finding.id = "U" + AiEvidence.fingerprint(activeRoute.id + '\n' + finding.id).substring(0, 16);
		}
		Section section = section(finding.kind);
		if (!hasRoom(finding.kind)) {
			gap("finding_budget");
			limit(finding.kind, "finding_budget");
			return false;
		}
		if (section.findings.stream().noneMatch(value -> value.id.equals(finding.id))) {
			section.findings.add(finding);
			if (activeRoute != null) {
				routeSection(finding.kind).findings.add(finding);
			}
		}
		return true;
	}

	boolean hasRoom(Kind kind) {
		return (activeRoute == null ? section(kind) : routeSection(kind)).findings.size() < 40
				&& sections.stream().mapToInt(section -> section.findings.size()).sum() < 320;
	}

	private Section routeSection(Kind kind) {
		return activeRoute.sections.stream().filter(section -> section.kind == kind).findFirst().orElseThrow();
	}

	private Section section(Kind kind) {
		return sections.stream().filter(section -> section.kind == kind).findFirst().orElseThrow();
	}

	public FrameworkAnalysis snapshot() {
		FrameworkAnalysis copy = new FrameworkAnalysis();
		copy.framework = framework;
		copy.gaps = new ArrayList<>(gaps);
		copy.prioritizedResources = new ArrayList<>(prioritizedResources);
		copy.resourcesRead = resourcesRead;
		copy.complete = complete;
		for (Route route : routes) {
			Route target = new Route(route.id, route.name, route.evidence, route.nextAction);
			target.status = route.status;
			target.gaps = new ArrayList<>(route.gaps);
			target.prioritizedResources = new ArrayList<>(route.prioritizedResources);
			for (int i = 0; i < route.sections.size(); i++) {
				target.sections.get(i).covered = route.sections.get(i).covered;
				target.sections.get(i).findings = new ArrayList<>(route.sections.get(i).findings);
				target.sections.get(i).limitations = new ArrayList<>(route.sections.get(i).limitations);
			}
			copy.routes.add(target);
		}
		for (Section section : sections) {
			Section target = copy.section(section.kind);
			target.covered = section.covered;
			target.findings = new ArrayList<>(section.findings);
			target.limitations = new ArrayList<>(section.limitations);
		}
		return copy;
	}

	public String markdown() {
		if (!complete && resourcesRead == 0) {
			return "";
		}
		StringBuilder text = new StringBuilder("\n## Framework routes · ").append(getFramework()).append("\n\n");
		for (Route route : routes) {
			text.append("- ").append(route.name).append(": ").append(route.status).append(" · ").append(route.nextAction).append('\n');
		}
		for (Section section : sections) {
			text.append("- ").append(section.kind).append(": ").append(section.getStatus()).append(" · ")
					.append(section.findings.size()).append("\n");
		}
		for (Finding finding : getFindings()) {
			text.append("\n### ").append(finding.id).append(" · ").append(finding.title.replace('\n', ' ')).append("\n\n")
					.append(finding.summary).append("\n\n");
			for (AiEvidence source : finding.evidence) {
				text.append("````text\n").append(source.getTitle().replace("````", "` ` ` `")).append('\n')
						.append(source.getCode().replace("````", "` ` ` `")).append("\n````\n");
			}
		}
		gaps.forEach(gap -> text.append("- ").append(gap).append('\n'));
		return text.toString();
	}
}
