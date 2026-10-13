package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/** Presents existing facts without promoting method co-occurrence to request data flow. */
final class CommunicationEvidence {
	private CommunicationEvidence() {
	}

	static List<AiEvidence> crypto(AnalysisReport report, ApiEndpoint endpoint, ApiDossier dossier) {
		if (report == null || endpoint == null) {
			return List.of();
		}
		Set<JadxNodeRef> methods = new HashSet<>();
		endpoint.getSources().forEach(e -> methods.add(e.getNode()));
		if (dossier != null) {
			dossier.getEvidence().forEach(e -> methods.add(e.getNode()));
			if (dossier.getCallPath() != null) {
				dossier.getCallPath().getNodes().forEach(n -> methods.add(n.getMethod()));
			}
		}
		methods.remove(null);
		List<AiEvidence> matches = new ArrayList<>();
		for (AnalysisFinding finding : report.getFindings()) {
			if (finding.getKind() == AnalysisFinding.Kind.CRYPTO) {
				for (AiEvidence evidence : finding.getEvidence()) {
					if (matches.size() < 32 && methods.contains(evidence.getNode())
							&& matches.stream().noneMatch(e -> e.getTitle().equals(evidence.getTitle()))) {
						matches.add(evidence);
					}
				}
			}
		}
		return matches;
	}

	static String cryptoDescription(List<AiEvidence> evidence) {
		if (evidence.isEmpty()) {
			return CommunicationText.str("crypto_empty");
		}
		StringBuilder text = new StringBuilder(CommunicationText.str("crypto_boundary")).append("\n\n");
		for (AiEvidence source : evidence) {
			text.append(source.getTitle()).append('\n').append(source.getCode()).append("\n\n");
		}
		return text.toString();
	}

	static String outlet(ApiEndpoint endpoint, ApiDossier dossier) {
		if (endpoint == null) {
			return CommunicationText.str("select");
		}
		StringBuilder text = new StringBuilder(AiText.str("api.address")).append(": ")
				.append(endpoint.getAddress().isEmpty() ? AiText.str("api.runtime_url") : endpoint.getAddress()).append("\n\n");
		if (dossier == null || dossier.getBindings().isEmpty()) {
			text.append(CommunicationText.str("outlet_empty")).append('\n');
		} else {
			for (ApiDossier.Binding binding : dossier.getBindings()) {
				text.append(AiText.str("api.relation.client")).append(": ").append(binding.getFactory()).append('\n')
						.append(AiText.str("api.host")).append(": ")
						.append(binding.getBaseUrl().isBlank() ? AiText.str("api.base_unknown") : binding.getBaseUrl()).append('\n')
						.append(AiText.str("api.relation.interceptors")).append(": ")
						.append(binding.getInterceptors().isEmpty()
								? AiText.str("api.auth_unknown")
								: String.join("\n", binding.getInterceptors()))
						.append("\n\n");
			}
		}
		if (dossier != null) {
			dossier.getGaps().forEach(gap -> text.append("\n• ").append(gap));
		}
		return text.append("\n\n").append(CommunicationText.str("static_only")).toString();
	}
}
