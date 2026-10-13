package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.InsnIndexType;
import jadx.api.utils.CodeUtils;
import jadx.core.dex.info.MethodInfo;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiCodeChunks;
import jadx.gui.ai.AiContext;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/** Local, bounded read operations shared by interactive investigation and MCP. */
public final class LocalAnalysisTools {
	public static final Set<String> OPERATIONS = Set.of("inspect_symbol", "find_references", "trace_calls", "search_artifacts",
			"inspect_api", "read_evidence");
	private final JadxDecompiler decompiler;

	public LocalAnalysisTools(JadxDecompiler decompiler) {
		this.decompiler = decompiler;
	}

	public JavaNode resolve(JadxNodeRef ref) {
		return AiContext.resolve(decompiler, ref);
	}

	public ApiDossier api(JavaMethod method, AiCancellation token) throws InterruptedException {
		ApiEndpoint endpoint = ApiExtractor.method(method.getMethodNode(), method.getFullName(), JadxNodeRef.forMth(method));
		if (endpoint == null) {
			throw new IllegalArgumentException(AiText.str("api.relation.declaration_required"));
		}
		AnalysisReport inventory = new AnalysisReport();
		inventory.addApi(endpoint);
		return new ApiRelationResolver(decompiler).resolve(endpoint, token);
	}

	public static final class Source {
		private final AiEvidence evidence;
		private final boolean code;

		Source(AiEvidence evidence, boolean code) {
			this.evidence = evidence;
			this.code = code;
		}

		public AiEvidence getEvidence() {
			return evidence;
		}

		public boolean isCode() {
			return code;
		}
	}

	public Source inspect(JadxNodeRef ref, AiCancellation token) throws InterruptedException {
		token.check();
		JavaNode node = resolve(ref);
		if (node == null) {
			throw new IllegalArgumentException(AiText.str("investigation.node_missing"));
		}
		ICodeInfo info = node.getTopParentClass().getCodeInfo();
		token.check();
		boolean body = node instanceof JavaMethod && !((JavaMethod) node).getMethodNode().isNoCode();
		String text = node instanceof JavaMethod
				? CodeUtils.extractMethodCode(((JavaMethod) node).getMethodNode(), info)
				: info.getCodeStr();
		if (text.isBlank() && node instanceof JavaMethod) {
			int position = ((JavaMethod) node).getMethodNode().getDefPosition();
			if (position > 0 && position < info.getCodeStr().length()) {
				text = CodeUtils.getLineForPos(info.getCodeStr(), position);
			}
			body = false;
		}
		String clean = AnalysisRedactor.redact(text);
		return new Source(new AiEvidence(ref, ref + (body ? " [definition]" : " [declaration/source]"),
				AiCodeChunks.prefix(clean, 6000), AiEvidence.fingerprint(text), clean.length() > 6000), body);
	}

	public List<JadxNodeRef> references(JadxNodeRef ref, AiCancellation token) throws InterruptedException {
		token.check();
		JavaNode node = resolve(ref);
		if (node == null) {
			throw new IllegalArgumentException(AiText.str("investigation.node_missing"));
		}
		List<JadxNodeRef> result = new ArrayList<>();
		for (JavaNode use : node.getUseIn()) {
			token.check();
			JadxNodeRef target = JadxNodeRef.forJavaNode(use);
			if (target != null && !result.contains(target)) {
				result.add(target);
			}
			if (result.size() > 32) {
				break;
			}
		}
		return result;
	}

	/** Bytecode-declared outgoing targets, not a proof of runtime dispatch or reachability. */
	public List<JadxNodeRef> calls(JadxNodeRef ref, AiCancellation token) throws InterruptedException {
		token.check();
		JavaNode node = resolve(ref);
		if (!(node instanceof JavaMethod)) {
			throw new IllegalArgumentException(AiText.str("investigation.method_required"));
		}
		ICodeReader reader = ((JavaMethod) node).getMethodNode().getCodeReader();
		if (reader == null) {
			throw new IllegalArgumentException(AiText.str("investigation.no_body"));
		}
		if (reader.getUnitsCount() > 200_000) {
			throw new IllegalArgumentException(AiText.str("analysis.method_large") + ref);
		}
		Set<JadxNodeRef> targets = new LinkedHashSet<>();
		try {
			reader.copy().visitInstructions(insn -> {
				if (token.isCancelled() || Thread.currentThread().isInterrupted()) {
					throw new CancellationException();
				}
				insn.decode();
				if (insn.getIndexType() == InsnIndexType.METHOD_REF && targets.size() <= 32) {
					IMethodRef called = insn.getIndexAsMethod();
					if (called != null) {
						called.load();
						MethodInfo method = MethodInfo.fromRef(decompiler.getRoot(), called);
						targets.add(new JadxNodeRef(RefType.METHOD, method.getDeclClass().getRawName(), method.getShortId()));
					}
				}
			});
		} catch (CancellationException e) {
			token.check();
			throw e;
		}
		return new ArrayList<>(targets);
	}

	public String execute(String operation, String target, String query, InvestigationRun run, AnalysisReport report,
			AiCancellation token) throws InterruptedException {
		if (!OPERATIONS.contains(operation)) {
			throw new IllegalArgumentException("Unknown local operation");
		}
		token.check();
		String key = operation + '\n' + target + '\n' + query;
		if (run.completed(key)) {
			return AiText.str("investigation.reused");
		}
		List<String> retained = new ArrayList<>();
		String summary;
		if (operation.equals("search_artifacts")) {
			summary = search(query, run, report, token);
			retained.add(run.retain(operation, new AiEvidence(null, "Search: " + query, AiCodeChunks.prefix(summary, 6000),
					AiEvidence.fingerprint(summary), summary.length() > 6000), List.of(), false, Set.of()));
		} else if (operation.equals("read_evidence")) {
			EvidenceStore.Entry entry = run.getEvidence().get(target);
			summary = entry.getSource().getCode();
			retained.add(target);
		} else if (operation.equals("inspect_api")) {
			ApiEndpoint endpoint = report.getApis().stream().filter(e -> e.getId().equals(target)).findFirst()
					.orElseThrow(() -> new IllegalArgumentException("Unknown API identifier"));
			ApiDossier dossier = new ApiRelationResolver(decompiler).resolve(endpoint, token);
			summary = dossier.describe();
			Set<String> owners = run.getSelected().contains(target) ? Set.of(target) : Set.of();
			if (dossier.getCallPath() != null) {
				StringBuilder nodes = new StringBuilder("\nCall-path methods in this investigation:\n");
				for (CallPath.Node node : dossier.getCallPath().getNodes()) {
					String id = run.register(node.getMethod(), owners);
					nodes.append(node.getId()).append(" = ").append(id.isEmpty() ? "catalog limit" : id).append('\n');
				}
				summary += nodes;
			}
			retained.add(run.retain(operation, new AiEvidence(endpoint.getSources().get(0).getNode(), target + " [API relations]",
					AiCodeChunks.prefix(summary, 6000), AiEvidence.fingerprint(summary), summary.length() > 6000), List.of(), false,
					owners));
			for (AiEvidence source : dossier.getEvidence()) {
				retained.add(run.retain(operation, source, List.of(retained.get(0)), false, owners));
			}
		} else {
			JadxNodeRef ref = run.getNodes().get(target);
			if (ref == null) {
				throw new IllegalArgumentException("Use a node identifier from the current investigation catalog");
			}
			Set<String> owners = run.owners(target);
			if (operation.equals("inspect_symbol")) {
				Source source = inspect(ref, token);
				retained.add(run.retain(operation, source.evidence, List.of(), source.code, owners));
				summary = source.evidence.getTitle();
			} else {
				List<JadxNodeRef> refs = operation.equals("find_references") ? references(ref, token) : calls(ref, token);
				StringBuilder text = new StringBuilder(operation.equals("find_references")
						? "Incoming static references (not necessarily invocations):\n"
						: "Bytecode-declared outgoing calls:\n");
				for (JadxNodeRef related : refs.subList(0, Math.min(32, refs.size()))) {
					String id = resolve(related) == null ? "external/unresolved" : run.register(related, owners);
					text.append(id.isEmpty() ? "catalog limit" : id).append(" | ").append(related).append('\n');
				}
				text.append("Coverage: one level; reflection, native code and runtime dispatch are not resolved.");
				if (refs.isEmpty()) {
					text.append(" No targets found within this operation; absence is not established.");
				}
				if (refs.size() > 32) {
					text.append(" Target list truncated at 32.");
				}
				summary = text.toString();
				retained.add(run.retain(operation,
						new AiEvidence(ref, ref + " [" + operation + ']', AiCodeChunks.prefix(summary, 6000),
								AiEvidence.fingerprint(summary), refs.size() > 32 || summary.length() > 6000),
						List.of(), false, owners));
			}
		}
		token.check();
		run.step(operation, target.isEmpty() ? query : target, AiCodeChunks.prefix(summary, 1500), retained, key);
		return summary;
	}

	private String search(String query, InvestigationRun run, AnalysisReport report, AiCancellation token) throws InterruptedException {
		if (query.isBlank() || query.length() > 200) {
			throw new IllegalArgumentException("Search needs 1 to 200 characters");
		}
		String needle = query.toLowerCase(Locale.ROOT);
		List<String> hits = new ArrayList<>();
		List<AnalysisFinding> inventory = new ArrayList<>(report.getFindings());
		report.getApis().forEach(api -> inventory.add(api.asFinding()));
		for (AnalysisFinding lead : inventory) {
			token.check();
			if ((lead.getValue() + ' ' + lead.getEvidence().get(0).getTitle()).toLowerCase(Locale.ROOT).contains(needle)) {
				AiEvidence source = lead.getEvidence().get(0);
				String node = run.register(source.getNode(), run.getSelected().contains(lead.getId()) ? Set.of(lead.getId()) : Set.of());
				hits.add(lead.getId() + " | " + node + " | " + AiCodeChunks.prefix(source.getTitle(), 200));
			}
			if (hits.size() >= 20) {
				break;
			}
		}
		int visited = 0;
		for (JavaClass cls : decompiler.getClassesWithInners()) {
			token.check();
			if (hits.size() >= 20 || ++visited > 50_000) {
				break;
			}
			if ((cls.getRawName() + ' ' + cls.getFullName()).toLowerCase(Locale.ROOT).contains(needle)) {
				hits.add(run.register(JadxNodeRef.forCls(cls), Set.of()) + " | " + AiCodeChunks.prefix(cls.getRawName(), 200));
			}
		}
		return String.join("\n", hits) + "\nCoverage: existing scan leads and class names; at most 20 matches / 50000 classes. "
				+ "Not a full source search. No matches does not establish absence.";
	}

	public String catalog(InvestigationRun run, AnalysisReport report) {
		StringBuilder out = new StringBuilder("Known nodes:\n");
		int shown = 0;
		for (var node : run.getNodes().entrySet()) {
			if (++shown > 80 || out.length() > 12_000) {
				break;
			}
			out.append(node.getKey()).append(" | ").append(AiCodeChunks.prefix(node.getValue().toString(), 200)).append('\n');
		}
		out.append("Selected APIs:\n");
		report.getApis().stream().filter(e -> run.getSelected().contains(e.getId())).limit(6)
				.forEach(e -> out.append(e.getId()).append(" | ").append(e.getMethod()).append(' ').append(e.getAddress()).append('\n'));
		return out.toString();
	}
}
