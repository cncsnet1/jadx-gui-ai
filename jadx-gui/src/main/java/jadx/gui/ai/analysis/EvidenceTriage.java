package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import jadx.gui.ai.AiEvidence;

/**
 * Deterministic presentation priority, never a vulnerability verdict or proof of code ownership.
 */
public final class EvidenceTriage {

	public enum Tier {
		PRIMARY, SUPPORTING, BACKGROUND
	}

	public enum Origin {
		APP_NAMESPACE, LIBRARY_NAMESPACE, FRAMEWORK_RUNTIME, UNKNOWN
	}

	public enum Signal {
		DECLARATION, CALL_REFERENCE, RESOURCE_SOURCE, LITERAL, INVENTORY
	}

	public static final class Decision {
		private final Tier tier;
		private final Origin origin;
		private final Signal signal;
		private final List<String> reasons;
		private final int score;

		private Decision(Tier tier, Origin origin, Signal signal, List<String> reasons, int score) {
			this.tier = tier;
			this.origin = origin;
			this.signal = signal;
			this.reasons = List.copyOf(reasons);
			this.score = score;
		}

		public Tier getTier() {
			return tier;
		}

		public Origin getOrigin() {
			return origin;
		}

		public Signal getSignal() {
			return signal;
		}

		public List<String> getReasons() {
			return reasons;
		}

		/** Higher scores should be shown first. */
		public int getScore() {
			return score;
		}
	}

	private static final List<String> LIBRARY_PREFIXES = List.of(
			"android", "androidx", "java", "javax", "kotlin", "kotlinx", "okhttp3", "okio", "retrofit2",
			"org.apache", "org.json", "org.jetbrains", "org.bouncycastle", "io.reactivex", "rx",
			"com.google.android.gms", "com.google.firebase", "com.google.gson", "com.google.protobuf",
			"com.squareup", "com.bumptech.glide", "com.facebook.fresco", "com.facebook.soloader",
			"com.tencent.mm.opensdk", "com.umeng", "cn.jpush", "cn.jiguang");
	private static final List<String> RUNTIME_PREFIXES = List.of("io.flutter", "io.dcloud", "com.facebook.react", "com.taobao.weex");
	private static final Set<String> RUNTIME_FILES = Set.of("libflutter.so", "libreactnative.so", "libhermes.so",
			"uni-app-view.umd.js", "uni-app-view.js", "uni-app-service.js", "uni.webview.js", "uni.webview.1.5.6.js",
			"vue.min.js", "vue.runtime.esm.js", "weex-js-runtime.js");
	private static final Pattern AUTH_CALL = Pattern.compile(
			"(?m)^[\\p{javaJavaIdentifierPart}/.$]+;?\\.(?:login|signIn|signin|signInWithPassword|signInWithCredential|authenticate)(?:\\([^\\r\\n]*\\))?\\s*$");

	private EvidenceTriage() {
	}

	public static Decision assess(AnalysisReport report, AnalysisFinding finding) {
		Origin origin = Origin.UNKNOWN;
		int best = Integer.MIN_VALUE;
		for (AiEvidence source : finding.getEvidence()) {
			if (indirectResourceReference(source)) {
				continue;
			}
			Origin candidate = origin(report, source);
			int value = originScore(candidate);
			if (value > best) {
				best = value;
				origin = candidate;
			}
		}
		Signal signal = signal(finding);
		List<String> reasons = new ArrayList<>();
		reasons.add(originReason(origin));
		reasons.add(signal.name().toLowerCase(Locale.ROOT));
		boolean weak = finding.getKind() == AnalysisFinding.Kind.ENDPOINT
				|| finding.getKind() == AnalysisFinding.Kind.PUBLIC_CONFIG
				|| finding.getKind() == AnalysisFinding.Kind.APK_STRUCTURE
				|| finding.getKind() == AnalysisFinding.Kind.LOGIN && signal == Signal.LITERAL;
		if (finding.getKind() == AnalysisFinding.Kind.LOGIN && signal == Signal.LITERAL) {
			reasons.add("login_keyword_only");
		}
		if (finding.getKind() == AnalysisFinding.Kind.ENDPOINT) {
			reasons.add("url_without_request");
		}
		if (finding.getKind() == AnalysisFinding.Kind.PUBLIC_CONFIG) {
			reasons.add("public_configuration");
		}
		boolean secretCandidate = finding.getKind().group().equals("secret");
		if (secretCandidate) {
			reasons.add("secret_candidate_unverified");
		}
		Tier tier;
		if (secretCandidate && !weak) {
			tier = Tier.SUPPORTING;
		} else if (origin == Origin.LIBRARY_NAMESPACE || origin == Origin.FRAMEWORK_RUNTIME || weak) {
			tier = Tier.BACKGROUND;
		} else if (signal == Signal.DECLARATION || origin == Origin.APP_NAMESPACE && signal == Signal.CALL_REFERENCE
				|| finding.getKind() == AnalysisFinding.Kind.FRAMEWORK_CODE) {
			tier = Tier.PRIMARY;
		} else {
			tier = Tier.SUPPORTING;
		}
		int score = (tier == Tier.PRIMARY ? 200 : tier == Tier.SUPPORTING ? 100 : 0)
				+ signalScore(signal) + originScore(origin);
		return new Decision(tier, origin, signal, reasons, score);
	}

	/** Sort a copy without filtering observations, altering values, or changing stable IDs. */
	public static List<AnalysisFinding> ranked(AnalysisReport report, AnalysisGoal goal, List<AnalysisFinding> findings) {
		List<AnalysisFinding> ordered = new ArrayList<>(findings);
		ordered.sort(comparator(report, goal));
		return ordered;
	}

	public static Comparator<AnalysisFinding> comparator(AnalysisReport report, AnalysisGoal goal) {
		return Comparator.<AnalysisFinding>comparingInt(finding -> {
			Decision decision = assess(report, finding);
			boolean matchesGoal = finding.priority(goal) < finding.getKind().priority();
			return decision.getScore() + (matchesGoal ? decision.getTier() == Tier.BACKGROUND ? 20 : 500 : 0);
		}).reversed()
				.thenComparingInt(finding -> finding.priority(goal));
	}

	/**
	 * Used before bytecode scanning; an unmatched/obfuscated namespace stays ahead of library
	 * candidates.
	 */
	public static int classRank(AnalysisReport report, String rawClassName) {
		return originScore(classOrigin(report, rawClassName));
	}

	/** Prefer representative application sources within the bounded snippet preview. */
	public static int sourceScore(AnalysisReport report, AiEvidence source) {
		return indirectResourceReference(source) ? -50 : originScore(origin(report, source));
	}

	private static boolean indirectResourceReference(AiEvidence source) {
		return source.getTitle().endsWith(" [resource reference]")
				&& source.getCode().startsWith("Resource filename reference in method:");
	}

	/** Exact source locality only; this does not establish invocation, reachability, or data flow. */
	public static boolean sameSource(AiEvidence first, AiEvidence second) {
		if (first.getNode() != null && second.getNode() != null) {
			String className = first.getNode().getDeclaringClass();
			return className != null && !className.isEmpty() && className.equals(second.getNode().getDeclaringClass());
		}
		if (first.getNode() != null || second.getNode() != null) {
			return false;
		}
		String a = resourceIdentity(first.getTitle());
		String b = resourceIdentity(second.getTitle());
		return a.matches("(?i).*\\.(?:js|json|xml|html?|vue|nvue|properties|ya?ml|conf|ini|txt|pem|key|dex|so|pb|arsc|bundle|hbc|dll)")
				&& a.equals(b);
	}

	private static Origin origin(AnalysisReport report, AiEvidence source) {
		if (source.getNode() != null) {
			return classOrigin(report, source.getNode().getDeclaringClass());
		}
		String resource = resourceIdentity(source.getTitle()).replace('\\', '/').toLowerCase(Locale.ROOT);
		String base = resource.substring(resource.lastIndexOf('/') + 1);
		return RUNTIME_FILES.contains(base) || resource.contains("/node_modules/") || resource.contains("/uni-jsframework/")
				? Origin.FRAMEWORK_RUNTIME
				: Origin.UNKNOWN;
	}

	private static Origin classOrigin(AnalysisReport report, String name) {
		if (name == null || name.isBlank()) {
			return Origin.UNKNOWN;
		}
		String raw = name.replace('/', '.');
		if (report != null && inNamespace(raw, report.getAppPackage())) {
			return Origin.APP_NAMESPACE;
		}
		if (RUNTIME_PREFIXES.stream().anyMatch(prefix -> inNamespace(raw, prefix))) {
			return Origin.FRAMEWORK_RUNTIME;
		}
		return LIBRARY_PREFIXES.stream().anyMatch(prefix -> inNamespace(raw, prefix)) ? Origin.LIBRARY_NAMESPACE : Origin.UNKNOWN;
	}

	private static boolean inNamespace(String name, String prefix) {
		return prefix != null && !prefix.isBlank() && (name.equals(prefix) || name.startsWith(prefix + '.'));
	}

	private static String resourceIdentity(String title) {
		return title == null ? "" : title.replaceFirst(":\\d+(?::\\d+)?(?: ·.*)?$", "");
	}

	private static Signal signal(AnalysisFinding finding) {
		switch (finding.getKind()) {
			case HTTP_ROUTE:
			case EXPORTED:
			case DEBUGGABLE:
			case BACKUP:
				return Signal.DECLARATION;
			case REQUEST_BUILDER:
			case CRYPTO:
			case DYNAMIC:
			case WEBVIEW:
				return Signal.CALL_REFERENCE;
			case FRAMEWORK_CODE:
				return Signal.RESOURCE_SOURCE;
			case APK_STRUCTURE:
				return Signal.INVENTORY;
			case LOGIN:
				return finding.getEvidence().stream()
						.anyMatch(source -> source.getNode() != null && AUTH_CALL.matcher(source.getCode()).find())
								? Signal.CALL_REFERENCE
								: Signal.LITERAL;
			default:
				return Signal.LITERAL;
		}
	}

	private static String originReason(Origin origin) {
		switch (origin) {
			case APP_NAMESPACE:
				return "app_namespace_match";
			case LIBRARY_NAMESPACE:
				return "library_namespace_candidate";
			case FRAMEWORK_RUNTIME:
				return "framework_runtime_marker";
			default:
				return "origin_unknown";
		}
	}

	private static int originScore(Origin origin) {
		switch (origin) {
			case APP_NAMESPACE:
				return 30;
			case UNKNOWN:
				return 0;
			case LIBRARY_NAMESPACE:
				return -30;
			default:
				return -40;
		}
	}

	private static int signalScore(Signal signal) {
		switch (signal) {
			case DECLARATION:
				return 40;
			case CALL_REFERENCE:
				return 30;
			case RESOURCE_SOURCE:
				return 20;
			case LITERAL:
				return 10;
			default:
				return 0;
		}
	}
}
