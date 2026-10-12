package jadx.gui.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.utils.CodeUtils;
import jadx.core.dex.nodes.ClassNode;
import jadx.gui.ui.codearea.CodeArea;

public final class AiContext {
	private static final int PRIMARY_LIMIT = 12_000;
	private static final int RELATED_LIMIT = 3_000;
	private static final int MAX_RELATED = 3;

	private AiContext() {
	}

	/** Capture only already-rendered source on the EDT. No network or decompilation. */
	public static AiEvidence capture(CodeArea area) {
		if (area == null || area.isDisposed() || area.getJClass() == null || area.getDocument().getLength() == 0) {
			return null;
		}
		JavaNode node = area.getEnclosingJavaNode(area.getCaretPosition());
		if (!(node instanceof JavaMethod)) {
			node = area.getJClass().getJavaNode();
		}
		String code = area.getSelectedText();
		ICodeInfo info = area.getCodeInfo();
		if (code == null || code.isBlank()) {
			code = extract(node, info);
		}
		return evidence(node, code, info.getCodeStr(), PRIMARY_LIMIT);
	}

	public static List<AiEvidence> collect(JadxDecompiler decompiler, AiEvidence primary,
			boolean includeCallers, AiCancellation cancellation) throws InterruptedException {
		List<AiEvidence> result = new ArrayList<>();
		cancellation.check();
		result.add(primary);
		if (!includeCallers) {
			return result;
		}
		JavaNode node = resolve(decompiler, primary.getNode());
		if (node == null) {
			return result;
		}
		List<JavaNode> callers = new ArrayList<>(node.getUseIn());
		for (JavaNode caller : callers) {
			cancellation.check();
			if (!(caller instanceof JavaMethod) || primary.getNode().equals(JadxNodeRef.forJavaNode(caller))) {
				continue;
			}
			ICodeInfo info = caller.getTopParentClass().getCodeInfo();
			result.add(evidence(caller, extract(caller, info), info.getCodeStr(), RELATED_LIMIT));
			if (result.size() == MAX_RELATED + 1) {
				break;
			}
		}
		cancellation.check();
		return Collections.unmodifiableList(result);
	}

	/** Resolve serialized raw names/short IDs, which are different from live code metadata refs. */
	public static JavaNode resolve(JadxDecompiler decompiler, JadxNodeRef ref) {
		if (ref == null) {
			return null; // Resource evidence has no Java symbol.
		}
		ClassNode cls = decompiler.getRoot().resolveClass(ref.getDeclaringClass());
		if (cls == null) {
			return null;
		}
		switch (ref.getType()) {
			case CLASS:
				return decompiler.getJavaNodeByRef(cls);
			case METHOD:
				return decompiler.getJavaNodeByRef(cls.searchMethodByShortId(ref.getShortId()));
			case FIELD:
				return decompiler.getJavaNodeByRef(cls.searchFieldByShortId(ref.getShortId()));
			default:
				return null;
		}
	}

	private static String extract(JavaNode node, ICodeInfo info) {
		if (node instanceof JavaMethod) {
			String method = CodeUtils.extractMethodCode(((JavaMethod) node).getMethodNode(), info);
			if (!method.isBlank()) {
				return method;
			}
		}
		return info.getCodeStr();
	}

	private static AiEvidence evidence(JavaNode node, String code, String fullCode, int limit) {
		boolean truncated = code.length() > limit;
		String snippet = truncated ? AiCodeChunks.prefix(code, limit) : code;
		return new AiEvidence(JadxNodeRef.forJavaNode(node), node.getFullName(), snippet,
				AiEvidence.fingerprint(fullCode), truncated);
	}
}
