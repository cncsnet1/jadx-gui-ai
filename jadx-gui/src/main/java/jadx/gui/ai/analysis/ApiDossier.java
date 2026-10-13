package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/** Observed client construction paths; multiple bindings remain separate. */
public final class ApiDossier {
	public static final class Binding {
		private String baseUrl;
		private String factory;
		private List<String> interceptors;
		private JadxNodeRef source;

		private Binding() {
		}

		Binding(String baseUrl, String factory, List<String> interceptors, JadxNodeRef source) {
			this.baseUrl = AnalysisRedactor.safeUrl(baseUrl);
			this.factory = factory;
			this.interceptors = List.copyOf(interceptors);
			this.source = source;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public String getFactory() {
			return factory;
		}

		public List<String> getInterceptors() {
			return Collections.unmodifiableList(interceptors);
		}

		public JadxNodeRef getSource() {
			return source;
		}
	}

	private String endpointId;
	private List<Binding> bindings = new ArrayList<>();
	private List<AiEvidence> evidence = new ArrayList<>();
	private List<String> gaps = new ArrayList<>();
	private CallPath callPath;

	private ApiDossier() {
	}

	ApiDossier(String endpointId) {
		this.endpointId = endpointId;
	}

	public String getEndpointId() {
		return endpointId;
	}

	public List<Binding> getBindings() {
		return Collections.unmodifiableList(bindings);
	}

	public List<AiEvidence> getEvidence() {
		return Collections.unmodifiableList(evidence);
	}

	public List<String> getGaps() {
		return Collections.unmodifiableList(gaps);
	}

	public CallPath getCallPath() {
		return callPath;
	}

	void setCallPath(CallPath callPath) {
		this.callPath = callPath;
	}

	void add(Binding binding, AiEvidence source) {
		if (bindings.size() < 8) {
			bindings.add(binding);
			evidence.add(source);
		} else {
			gap(AiText.str("api.relation.limit"));
		}
	}

	void evidence(AiEvidence source) {
		if (evidence.size() < 16 && evidence.stream().noneMatch(e -> e.getNode() != null && e.getNode().equals(source.getNode()))) {
			evidence.add(source);
		}
	}

	void gap(String value) {
		if (gaps.size() < 16 && !gaps.contains(value)) {
			gaps.add(value);
		}
	}

	public String describe() {
		StringBuilder text = new StringBuilder(AiText.str("api.relation.title") + " · " + endpointId + "\n\n");
		if (bindings.isEmpty()) {
			text.append(AiText.str("api.relation.unresolved")).append('\n');
		}
		for (int i = 0; i < bindings.size(); i++) {
			Binding binding = bindings.get(i);
			text.append('\n').append(AiText.str("api.relation.client")).append(' ').append(i + 1).append('\n')
					.append(binding.factory).append('\n').append(AiText.str("api.host")).append(": ")
					.append(binding.baseUrl.isBlank() ? AiText.str("api.base_unknown") : binding.baseUrl).append('\n')
					.append(AiText.str("api.relation.interceptors")).append(": ")
					.append(binding.interceptors.isEmpty() ? AiText.str("api.auth_unknown") : String.join(", ", binding.interceptors))
					.append('\n');
		}
		text.append('\n').append(AiText.str("api.relation.boundary")).append('\n');
		for (String gap : gaps) {
			text.append("• ").append(gap).append('\n');
		}
		if (callPath != null) {
			text.append('\n').append(callPath.describe());
		}
		return text.toString();
	}
}
