package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.List;

import jadx.gui.ai.AiEvidence;

/** Context selection from observed source identity, never a claim of runtime reachability. */
final class InvestigationScope {
	private InvestigationScope() {
	}

	static List<AnalysisFinding> leads(AnalysisReport report, InvestigationRun run) {
		List<AnalysisFinding> inventory = new ArrayList<>(report.getFindings());
		report.getApis().forEach(api -> inventory.add(api.asFinding()));
		inventory.addAll(ProfileLead.candidates(report));
		List<AnalysisFinding> ranked = EvidenceTriage.ranked(report, run.getGoal(), inventory);
		if (run.getFocusId() == null || run.getFocusId().isBlank()) {
			// Broad investigations still start with useful observations instead of filling the context with
			// background strings.
			List<AnalysisFinding> useful = ranked.stream()
					.filter(f -> EvidenceTriage.assess(report, f).getTier() != EvidenceTriage.Tier.BACKGROUND).toList();
			return useful.isEmpty() ? ranked.stream().limit(3).toList() : useful.stream().limit(16).toList();
		}
		List<AiEvidence> anchors = anchors(report, run);
		List<AnalysisFinding> scoped = new ArrayList<>();
		ranked.stream().filter(f -> run.getSelected().contains(f.getId())).forEach(scoped::add);
		ranked.stream().filter(f -> !run.getSelected().contains(f.getId()) && related(sources(report, f.getId()), anchors))
				.limit(Math.max(0, 16 - scoped.size())).forEach(scoped::add);
		return scoped;
	}

	static List<AiEvidence> anchors(AnalysisReport report, InvestigationRun run) {
		List<AiEvidence> result = new ArrayList<>();
		run.getSelected().forEach(id -> result.addAll(sources(report, id)));
		for (EvidenceStore.Entry entry : run.getEvidence().entries()) {
			if (!run.evidenceOwners(entry.getId()).isEmpty()) {
				result.add(entry.getSource());
			}
		}
		run.getNodes().forEach((id, node) -> {
			if (!run.owners(id).isEmpty()) {
				result.add(new AiEvidence(node, node.toString(), "", "", false));
			}
		});
		return result;
	}

	static boolean related(List<AiEvidence> sources, List<AiEvidence> anchors) {
		return sources.stream().anyMatch(source -> anchors.stream().anyMatch(anchor -> sameSource(source, anchor)));
	}

	static boolean sameSource(AiEvidence first, AiEvidence second) {
		return EvidenceTriage.sameSource(first, second);
	}

	static List<AnalysisFinding> seeds(AnalysisReport report, List<AnalysisFinding> ranked) {
		if (!ranked.isEmpty() && ranked.stream().allMatch(f -> f.getKind() == AnalysisFinding.Kind.APK_STRUCTURE)) {
			// A structure-only project needs its protection, technology and visibility observations, even
			// runtime markers.
			return ProfileLead.candidates(report).stream().limit(3).toList();
		}
		List<AnalysisFinding> useful = ranked.stream()
				.filter(f -> EvidenceTriage.assess(report, f).getTier() != EvidenceTriage.Tier.BACKGROUND).toList();
		return (useful.isEmpty() ? ranked : useful).stream().limit(3).toList();
	}

	static String resourceTitle(String title) {
		return title.replaceFirst("^A[0-9]+ · ", "").replaceFirst(":\\d+(?::\\d+)?(?: ·.*)?$", "");
	}

	static List<AiEvidence> sources(AnalysisReport report, String id) {
		ApiEndpoint api = report.getApis().stream().filter(endpoint -> endpoint.getId().equals(id)).findFirst().orElse(null);
		if (api != null) {
			return api.getSources();
		}
		AnalysisFinding finding = InvestigationService.find(report, id);
		return finding == null ? List.of() : finding.getEvidence();
	}
}
