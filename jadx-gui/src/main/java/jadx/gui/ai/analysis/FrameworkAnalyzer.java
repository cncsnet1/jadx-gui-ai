package jadx.gui.ai.analysis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.ResourcesLoader;
import jadx.api.data.impl.JadxNodeRef;
import jadx.core.xmlgen.ResContainer;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.analysis.FrameworkAnalysis.Finding;
import jadx.gui.ai.analysis.FrameworkAnalysis.Kind;
import jadx.gui.ai.analysis.FrameworkAnalysis.Route;
import jadx.gui.ai.analysis.FrameworkAnalysis.RouteStatus;
import jadx.gui.ai.analysis.JsApiExtractor.Range;
import jadx.gui.ai.analysis.JsApiExtractor.Token;

/**
 * Framework-specific local pass over existing resources; never starts an interpreter or network
 * request.
 */
public final class FrameworkAnalyzer {
	private static final int RESOURCE_BYTES = 1024 * 1024;
	private static final int TOTAL_BYTES = 12 * RESOURCE_BYTES;
	private static final int MAX_RESOURCES = 40;
	private static final long TIME_LIMIT = 12_000_000_000L;
	private static final Set<String> CONFIG_NAMES = Set.of("pages.json", "manifest.json", "app-config.json", "app-config.js",
			"app-config-service.js", "__uniappconfig.js");
	private static final Set<String> RUNTIME_NAMES = Set.of("uni-app-view.umd.js", "uni-app-view.js", "uni-app-service.js",
			"uni.webview.js", "uni.webview.1.5.6.js", "vue.min.js", "vue.runtime.esm.js", "weex-js-runtime.js", "cordova.js");
	private static final Set<Kind> SCRIPT_KINDS = EnumSet.of(Kind.BUSINESS_SCRIPTS, Kind.REQUESTS, Kind.AUTH_STORAGE,
			Kind.INTERCEPTORS, Kind.NATIVE_BRIDGE);
	private static final Set<String> STORAGE_CALLS = Set.of("getStorage", "getStorageSync", "setStorage", "setStorageSync",
			"removeStorage", "removeStorageSync", "clearStorage", "clearStorageSync");
	private static final Set<String> PAGE_CALLS = Set.of("navigateTo", "redirectTo", "switchTab", "reLaunch");

	private FrameworkAnalyzer() {
	}

	public static FrameworkAnalysis analyze(ApkProfile profile, List<Path> inputs, List<ResourceFile> resources,
			AiCancellation token, Consumer<ApiEndpoint> apis) throws InterruptedException {
		token.check();
		FrameworkAnalysis result = new FrameworkAnalysis();
		detectRoutes(profile, result);
		long started = System.nanoTime();
		Map<Path, String> archives = archiveLabels(inputs);
		List<ResourceFile> candidates = new ArrayList<>();
		for (ResourceFile resource : resources) {
			token.check();
			if (result.getRoutes().stream().anyMatch(route -> matchesRoute(route.getId(), resource))) {
				if (runtime(resource.getOriginalName())) {
					for (Route route : result.getRoutes()) {
						if (matchesRoute(route.getId(), resource)) {
							result.activate(route.getId());
							result.gap("runtime_sdk_excluded");
						}
					}
				} else {
					candidates.add(resource);
				}
			}
		}
		candidates.sort(Comparator.comparingInt(FrameworkAnalyzer::priority).thenComparing(ResourceFile::getOriginalName));
		// Give coexisting routes a chance before one large bundle family consumes the shared budget.
		Set<ResourceFile> ordered = new java.util.LinkedHashSet<>();
		List<List<ResourceFile>> byRoute = new ArrayList<>();
		for (Route route : result.getRoutes()) {
			byRoute.add(candidates.stream().filter(resource -> matchesRoute(route.getId(), resource)).limit(MAX_RESOURCES)
					.collect(java.util.stream.Collectors.toList()));
		}
		for (int i = 0; i < MAX_RESOURCES; i++) {
			for (List<ResourceFile> routeResources : byRoute) {
				if (i < routeResources.size()) {
					ordered.add(routeResources.get(i));
				}
			}
		}
		ordered.addAll(candidates);
		candidates = new ArrayList<>(ordered);
		int bytesRead = 0;
		int attemptedResources = 0;
		boolean complete = true;
		Set<String> emittedApis = new java.util.HashSet<>();
		Consumer<ApiEndpoint> uniqueApis = api -> {
			if (emittedApis.add(api.identity())) {
				apis.accept(api);
			}
		};
		for (ResourceFile resource : candidates) {
			token.check();
			result.activate("");
			if (attemptedResources >= MAX_RESOURCES || bytesRead >= TOTAL_BYTES) {
				result.gap("resource_budget");
				complete = false;
				break;
			}
			if (System.nanoTime() - started > TIME_LIMIT) {
				result.gap("time_budget");
				complete = false;
				break;
			}
			String source = sourceName(resource, archives);
			int limit = Math.min(RESOURCE_BYTES, TOTAL_BYTES - bytesRead);
			attemptedResources++;
			boolean readAccounted = false;
			try {
				Read sourceText = readResource(resource, limit);
				token.check();
				bytesRead += sourceText.bytes;
				readAccounted = true;
				result.read(source);
				for (Route route : result.getRoutes()) {
					if (!matchesRoute(route.getId(), resource)) {
						continue;
					}
					result.activate(route.getId());
					result.routeRead(source);
					if (!sourceText.hermes.isEmpty() || !sourceText.textFormat) {
						String description = sourceText.hermes.isEmpty()
								? "Binary resource observed; no text or executable functions were recovered."
								: sourceText.hermes;
						result.routeEvidence(
								new AiEvidence(null, source, description, AiEvidence.fingerprint(description), sourceText.truncated));
						result.gap(sourceText.hermes.isEmpty() ? "binary_resource_not_decoded" : "hermes_bytecode_requires_decoder");
						continue;
					}
					if (sourceText.truncated) {
						result.gap("resource_prefix_only");
						complete = false;
					}
					String name = normalize(resource.getOriginalName());
					if (route.getId().equals("flutter")) {
						resourceConfiguration(result, source, sourceText.text, sourceText.truncated,
								"Flutter asset/configuration content; this does not recover Dart functions or native code.");
					} else if (name.endsWith(".xml") || name.endsWith(".json") && !route.getId().equals("uni-app")) {
						HybridConfigAnalyzer.analyze(result, source, sourceText.text, sourceText.truncated);
					} else {
						if (name.endsWith(".html") || name.endsWith(".htm")) {
							add(result, Kind.PAGES, basename(name),
									"Packaged HTML document read; runtime WebView navigation is not established.",
									"Inspect this document's script sources and follow real navigation/bridge calls into request code.",
									source, sourceText.text, 0, Math.min(sourceText.text.length(), 1800), sourceText.truncated);
						}
						analyzeSource(result, source, sourceText.text, sourceText.truncated, uniqueApis);
					}
				}
				if (result.getGaps().contains("resource_prefix_only")) {
					complete = false;
				}
			} catch (Exception e) {
				token.check();
				if (!readAccounted) {
					// A failing decoder may already have consumed its entire allowance.
					bytesRead += limit;
				}
				for (Route route : result.getRoutes()) {
					if (matchesRoute(route.getId(), resource)) {
						result.activate(route.getId());
						result.gap("resource_read_failed");
					}
				}
				complete = false;
			}
		}
		for (Route route : result.getRoutes()) {
			result.activate(route.getId());
			if (route.getResourcesRead() == 0 && !Set.of("android", "unity", "flutter").contains(route.getId())) {
				result.gap("no_business_resources");
			}
			for (Kind kind : Kind.values()) {
				result.limit(kind, "static_observation_only");
				if (!complete) {
					result.limit(kind, "partial_resource_coverage");
				}
			}
			boolean partial = !complete || route.getGaps().stream().anyMatch(gap -> !gap.equals("runtime_sdk_excluded"));
			result.routeStatus(
					route.getFindings().isEmpty() ? RouteStatus.INVENTORY_ONLY : partial ? RouteStatus.PARTIAL : RouteStatus.COMPLETE);
		}
		result.activate("");
		result.complete(complete);
		return result;
	}

	private static Read readResource(ResourceFile resource, int limit) throws Exception {
		if (resource.getType() == ResourceType.XML && resource.getZipEntry() != null
				&& resource.getZipEntry().getUncompressedSize() >= 0 && resource.getZipEntry().getUncompressedSize() <= limit) {
			ResContainer content = resource.loadContent();
			if (content != null && content.getDataType() == ResContainer.DataType.TEXT && content.getText() != null) {
				String text = content.getText().getCodeStr();
				return new Read(text.substring(0, Math.min(text.length(), limit)), text.length() > limit,
						(int) resource.getZipEntry().getUncompressedSize(), "", true);
			}
		}
		return ResourcesLoader.decodeStream(resource, (size, input) -> {
			byte[] bytes = input.readNBytes(limit + 1);
			String name = normalize(resource.getOriginalName());
			return new Read(new String(bytes, 0, Math.min(bytes.length, limit), StandardCharsets.UTF_8),
					bytes.length > limit || size > limit, Math.min(bytes.length, limit),
					ScriptContent.isHermes(bytes) ? ScriptContent.hermesDescription(bytes) : "",
					!name.endsWith(".bin") && !name.endsWith(".hbc") && ScriptContent.isText(bytes));
		});
	}

	private static void detectRoutes(ApkProfile profile, FrameworkAnalysis result) {
		Map<String, String> names = new LinkedHashMap<>();
		names.put("uniapp", "uni-app");
		names.put("react_native", "React Native");
		names.put("cordova", "Cordova");
		names.put("webview", "WebView");
		names.put("flutter", "Flutter");
		names.put("unity", "Unity");
		names.put("android", "Android / Java / Kotlin");
		for (Map.Entry<String, String> entry : names.entrySet()) {
			for (ApkProfile.Fact fact : profile.getFacts()) {
				if (!fact.getCode().equals("technology_" + entry.getKey()) || fact.getConfidence() == ApkProfile.Confidence.UNKNOWN
						|| fact.getEvidence().isEmpty()) {
					continue;
				}
				String id = entry.getKey().equals("uniapp") ? "uni-app" : entry.getKey().replace('_', '-');
				String task = id.equals("unity")
						? "Inspect managed/IL2CPP artifact inventory; C# recovery and IL2CPP mapping require a decoder."
						: id.equals("flutter")
								? "Inspect asset configuration, libapp ELF evidence and platform-channel callers; Dart AOT functions remain undecoded."
						: id.equals("android")
								? "Follow application API declarations, manifest entries, cryptography and bridge call evidence."
						: "Follow readable business scripts and observed requests, storage and native bridge calls; resolve missing source explicitly.";
				result.route(id, entry.getValue(), fact.getEvidence(), task);
				result.activate(id);
				if (id.equals("unity") || id.equals("flutter")) {
					result.gap(id.equals("unity") ? "managed_il2cpp_decoder_unavailable" : "dart_aot_not_decompiled");
					for (ApkProfile.NativeLibrary library : profile.getNativeLibraries()) {
						String path = normalize(library.getPath());
						if (id.equals("flutter") && (path.endsWith("/libapp.so") || path.endsWith("/libflutter.so"))
								|| id.equals("unity") && (path.endsWith("/libil2cpp.so") || path.endsWith("/libunity.so"))) {
							library.getEvidence().forEach(result::routeEvidence);
						}
					}
				}
			}
		}
		result.activate("");
	}

	private static boolean matchesRoute(String route, ResourceFile resource) {
		String name = normalize(resource.getOriginalName());
		String base = basename(name);
		switch (route) {
			case "uni-app":
				return candidate(resource) && (name.matches("assets/apps/[^/]+/www/.*") || name.startsWith("assets/uniapp/")
						|| CONFIG_NAMES.contains(base) || base.equals("app-service.js"));
			case "react-native":
				return name.startsWith("assets/") && (name.endsWith(".bundle") || name.endsWith(".hbc")
						|| name.endsWith(".js") && !name.contains("/www/") && !name.contains("/flutter_assets/"));
			case "cordova":
				return name.equals("res/xml/config.xml") || name.startsWith("assets/www/")
						&& (JsApiExtractor.isSupportedResource(name) || base.equals("config.xml") || base.equals("config.json"));
			case "webview":
				return name.equals("res/xml/config.xml") || name.startsWith("assets/www/") && (JsApiExtractor.isSupportedResource(name)
						|| base.equals("config.xml") || base.equals("config.json"))
						|| name.startsWith("assets/") && (name.endsWith(".html") || name.endsWith(".htm"));
			case "flutter":
				return name.contains("/flutter_assets/") && (base.equals("assetmanifest.json") || base.equals("fontmanifest.json")
						|| base.equals("assetmanifest.bin") || base.equals("config.json") || base.endsWith(".env"));
			default:
				return false;
		}
	}

	static void resourceConfiguration(FrameworkAnalysis result, String source, String text, boolean truncated, String description) {
		result.covered(Kind.CONFIGURATION);
		add(result, Kind.CONFIGURATION, basename(source), description,
				"Inspect the actual configuration and identify resources or native entry points to read next; do not claim source recovery.",
				source, text, 0, Math.min(text.length(), 2400), truncated);
	}

	/**
	 * Reuses bytecode observations already obtained by the scanner; no class decompilation is started.
	 */
	static void observeNativeMethod(AnalysisReport report, JadxNodeRef ref, String calls, String literals) {
		String normalized = calls.replace('/', '.');
		List<String> routes = new ArrayList<>(4);
		if (normalized.contains("android.webkit.WebView")) {
			routes.add("webview");
		}
		if (normalized.contains("io.flutter.plugin.common.MethodChannel")
				|| normalized.contains("io.flutter.plugin.common.EventChannel")) {
			routes.add("flutter");
		}
		if (normalized.contains("com.facebook.react.bridge.")) {
			routes.add("react-native");
		}
		if (normalized.contains("org.apache.cordova.")) {
			routes.add("cordova");
		}
		if (routes.isEmpty() || !businessClass(report, ref.getDeclaringClass())) {
			return;
		}
		FrameworkAnalysis analysis = report.getFrameworkAnalysis();
		String code = "Observed method references (bytecode):\n" + calls + "\nString constants in the same method:\n" + literals
				+ "\nThese are co-located observations, not resolved argument flow or a cross-language call graph.";
		boolean truncated = code.length() > 6000;
		code = code.substring(0, Math.min(code.length(), 6000));
		AiEvidence evidence = new AiEvidence(ref, ref.toString(), code, AiEvidence.fingerprint(code), truncated);
		for (String route : routes) {
			String name = route.equals("webview") ? "WebView" : route.equals("flutter") ? "Flutter"
					: route.equals("cordova") ? "Cordova" : "React Native";
			analysis.route(route, name, List.of(evidence),
					"Read the application bridge caller, then inspect the corresponding packaged resource/native entry.");
			analysis.activate(route);
			analysis.routeEvidence(evidence);
			analysis.covered(Kind.NATIVE_BRIDGE);
			analysis.add(new Finding(Kind.NATIVE_BRIDGE, ref.toString(),
					"Application method contains framework bridge references; arguments remain untraced.",
					"Read this method and resolve actual bridge/channel names, arguments and registered handlers from source. "
							+ "Do not infer Dart/JavaScript/native target code from a string alone.",
					evidence));
			analysis.gap("cross_language_mapping_unresolved");
			analysis.routeStatus(RouteStatus.PARTIAL);
		}
		analysis.activate("");
	}

	/** Build the native route from existing, sourced observations after the bytecode pass finishes. */
	static void augmentNative(AnalysisReport report) {
		FrameworkAnalysis analysis = report.getFrameworkAnalysis();
		List<Finding> observed = new ArrayList<>();
		for (ApiEndpoint api : report.getApis()) {
			if (observed.size() >= 20 || api.getOrigin() != ApiEndpoint.Origin.DECLARATION) {
				continue;
			}
			for (AiEvidence source : api.getSources()) {
				if (source.getNode() == null || EvidenceTriage.classRank(report, source.getNode().getDeclaringClass()) < 0) {
					continue;
				}
				String code = source.getCode().isEmpty() ? "Declared API metadata:\n" + api.detail() : source.getCode();
				AiEvidence evidence = new AiEvidence(source.getNode(), source.getTitle(), code,
						AiEvidence.fingerprint(code), source.isTruncated() || api.isTruncated());
				observed.add(new Finding(Kind.REQUESTS, api.getMethod() + " " + api.getAddress(), "Observed Java/Kotlin API declaration.",
						"Read this declaration and find application callers, request interceptors and parameter construction using source evidence.",
						evidence));
				break;
			}
		}
		for (AnalysisFinding finding : report.getFindings()) {
			if (observed.size() >= 32 || !Set.of(AnalysisFinding.Kind.EXPORTED, AnalysisFinding.Kind.CRYPTO, AnalysisFinding.Kind.WEBVIEW)
					.contains(finding.getKind())) {
				continue;
			}
			for (AiEvidence source : finding.getEvidence()) {
				if (finding.getKind() != AnalysisFinding.Kind.EXPORTED
						&& (source.getNode() == null || !businessClass(report, source.getNode().getDeclaringClass()))) {
					continue;
				}
				Kind kind = finding.getKind() == AnalysisFinding.Kind.EXPORTED ? Kind.PAGES
						: finding.getKind() == AnalysisFinding.Kind.WEBVIEW ? Kind.NATIVE_BRIDGE : Kind.CRYPTO;
				observed.add(new Finding(kind, finding.getId() + " · " + finding.getTitle(),
						"Existing application observation; see the original evidence.",
						"Read the source behind this observation and trace application entry, request/cryptography or bridge behavior. "
								+ "This observation alone is not a vulnerability or a complete flow.",
						source));
				break;
			}
		}
		if (!observed.isEmpty()) {
			analysis.route("android", "Android / Java / Kotlin", observed.get(0).getEvidence(),
					"Follow application entry points and API declarations, then inspect their actual callers and request transformations.");
		}
		if (analysis.getRoutes().stream().anyMatch(route -> route.getId().equals("android"))) {
			analysis.activate("android");
			for (Finding finding : observed) {
				analysis.covered(finding.getKind());
				analysis.add(finding);
			}
			if (observed.isEmpty()) {
				analysis.gap("no_application_evidence");
			}
			analysis.routeStatus(observed.isEmpty() ? RouteStatus.INVENTORY_ONLY
					: report.getGaps().isEmpty() ? RouteStatus.COMPLETE : RouteStatus.PARTIAL);
		}
		// Native WebView callers may establish the route after the packaged-script pass. Reuse its real API
		// sources.
		if (analysis.getRoutes().stream().anyMatch(route -> route.getId().equals("webview"))) {
			analysis.activate("webview");
			for (ApiEndpoint api : report.getApis()) {
				for (AiEvidence source : api.getSources()) {
					if (source.getNode() == null && !source.getCode().isEmpty()
							&& source.getTitle().matches("(?i).*\\.(?:html?|js):[0-9]+:[0-9]+.*")) {
						analysis.add(new Finding(Kind.REQUESTS, api.getMethod() + " " + api.getAddress(),
								"Packaged script request observation; a specific WebView caller-to-resource link remains unresolved.",
								"Compare this script request source with the observed application WebView loads; establish exact resource identity before linking them.",
								source));
					}
				}
			}
		}
		analysis.activate("");
	}

	private static boolean businessClass(AnalysisReport report, String className) {
		if (className == null || className.isEmpty()) {
			return false;
		}
		int rank = EvidenceTriage.classRank(report, className);
		if (rank != 0) {
			return rank > 0;
		}
		for (AnalysisFinding finding : report.getFindings()) {
			if (finding.getKind() == AnalysisFinding.Kind.EXPORTED && finding.getValue().contains('"' + className + '"')) {
				return true;
			}
		}
		return false;
	}

	/** Used by the generic resource pass as well so business code receives the first API budget. */
	static int priority(ResourceFile resource) {
		String name = normalize(resource.getOriginalName());
		String base = basename(name);
		if (CONFIG_NAMES.contains(base)) {
			return 0;
		}
		if (base.equals("app-service.js") || name.contains("/pages/") || name.contains("/api/")
				|| name.contains("/request/") || name.contains("/utils/")) {
			return 1;
		}
		if (runtime(name)) {
			return 5;
		}
		return name.contains("/common/") || base.contains("vendor") ? 3 : candidate(resource) ? 2 : 4;
	}

	static boolean excludedRuntime(ResourceFile resource) {
		return runtime(resource.getOriginalName());
	}

	private static boolean candidate(ResourceFile resource) {
		String name = normalize(resource.getOriginalName());
		return CONFIG_NAMES.contains(basename(name)) || JsApiExtractor.isSupportedResource(name)
				&& (name.contains("/www/") || name.startsWith("assets/") || name.contains("/assets/"));
	}

	private static boolean runtime(String name) {
		String lower = normalize(name);
		return RUNTIME_NAMES.contains(basename(lower)) || lower.contains("/node_modules/") || lower.contains("/sdk/")
				|| lower.contains("/dcloud/") || lower.contains("/uni-jsframework/");
	}

	/** Package entry point for deterministic synthetic source tests; no external files are needed. */
	static void analyzeSource(FrameworkAnalysis result, String source, String text, boolean truncated, Consumer<ApiEndpoint> apis) {
		List<Token> tokens = JsApiExtractor.sourceTokens(text, source);
		boolean syntaxLimited = truncated || tokens.size() >= 150_000;
		String base = basename(normalize(source));
		if (CONFIG_NAMES.contains(base)) {
			result.covered(Kind.CONFIGURATION);
			result.covered(Kind.PAGES);
			add(result, Kind.CONFIGURATION, base, "Configuration source read locally; dynamic values remain unresolved.",
					"Read this configuration, list actual page/module declarations, and link to business resources. "
							+ "Distinguish configured entries from observed runtime calls.",
					source, text, 0, Math.min(text.length(), 1800), syntaxLimited);
			pages(result, source, text, tokens, syntaxLimited);
		}
		if (!JsApiExtractor.isSupportedResource(source)) {
			return;
		}
		SCRIPT_KINDS.forEach(result::covered);
		if (syntaxLimited) {
			SCRIPT_KINDS.forEach(kind -> result.limit(kind, "resource_prefix_only"));
			result.gap("resource_prefix_only");
		}
		if (!CONFIG_NAMES.contains(base)) {
			add(result, Kind.BUSINESS_SCRIPTS, base, "Business-script candidate read locally; filename does not prove business behavior.",
					"Inspect this business-script candidate. Find its entry functions and request wrapper, then read their actual source. "
							+ "Do not assume a call chain from names or nearby strings.",
					source, text, 0, Math.min(text.length(), 1200), syntaxLimited);
		}
		List<ApiEndpoint> endpoints = JsApiExtractor.scan(text, source, syntaxLimited);
		requestWrappers(result, source, text, tokens, endpoints, syntaxLimited);
		for (ApiEndpoint endpoint : endpoints) {
			apis.accept(endpoint);
			if (endpoint.isDynamicUrl()) {
				result.gap("dynamic_request_url");
				result.limit(Kind.REQUESTS, "dynamic_request_url");
			} else if (endpoint.getHost().isEmpty()) {
				result.limit(Kind.REQUESTS, "base_url_unresolved");
			}
			AiEvidence evidence = endpoint.getSources().get(0);
			String title = (endpoint.getMethod().isEmpty() ? "?" : endpoint.getMethod()) + " "
					+ (endpoint.getAddress().isEmpty() ? "runtime URL" : endpoint.getAddress());
			result.add(new Finding(Kind.REQUESTS, title,
					"Observed request call; " + endpoint.parameterSummary() + ". Reachability and caller data flow are unverified.",
					"Inspect the enclosing request wrapper and trace URL, parameters, headers, signing/encryption and response handling "
							+ "from actual source. Read missing source and retain unknown runtime values; do not infer a complete call chain.",
					evidence));
			for (AiEvidence constant : endpoint.getSources().stream().skip(1).collect(java.util.stream.Collectors.toList())) {
				result.add(new Finding(Kind.REQUESTS, "Request constant", "Same-scope literal constant used by a request expression.",
						"Read this constant with the request source and verify the static URL construction.", constant));
			}
		}
		calls(result, source, text, tokens, syntaxLimited);
	}

	private static void requestWrappers(FrameworkAnalysis result, String source, String text, List<Token> tokens,
			List<ApiEndpoint> endpoints, boolean truncated) {
		if (endpoints.isEmpty()) {
			return;
		}
		List<Integer> offsets = new ArrayList<>();
		for (ApiEndpoint endpoint : endpoints) {
			String title = endpoint.getSources().get(0).getTitle();
			int separator = title.lastIndexOf(" · ");
			int colon = title.lastIndexOf(':', separator);
			if (colon >= 0 && separator > colon) {
				offsets.add(Integer.parseInt(title.substring(colon + 1, separator)));
			}
		}
		for (int i = 0; i + 4 < tokens.size(); i++) {
			String name = "";
			int paren = -1;
			int body = -1;
			if (JsApiExtractor.at(tokens, i, "function") && tokens.get(i).kind == 'i') {
				if (tokens.get(i + 1).kind == 'i') {
					name = tokens.get(i + 1).text;
					paren = i + 2;
				} else if (JsApiExtractor.at(tokens, i - 1, "=") && i > 1 && tokens.get(i - 2).kind == 'i') {
					name = tokens.get(i - 2).text;
					paren = i + 1;
				}
				if (JsApiExtractor.at(tokens, paren, "(") && tokens.get(paren).end > paren) {
					body = tokens.get(paren).end + 1;
				}
			} else if (tokens.get(i).kind == 'i' && JsApiExtractor.at(tokens, i + 1, "=")
					&& JsApiExtractor.at(tokens, i + 2, "(") && tokens.get(i + 2).end > i + 2) {
				int close = tokens.get(i + 2).end;
				if (JsApiExtractor.at(tokens, close + 1, "=") && JsApiExtractor.at(tokens, close + 2, ">")) {
					name = tokens.get(i).text;
					body = close + 3;
				}
			}
			if (name.isEmpty() || !JsApiExtractor.at(tokens, body, "{") || tokens.get(body).end <= body) {
				continue;
			}
			int start = tokens.get(i).offset;
			int end = tokens.get(tokens.get(body).end).offset + 1;
			if (end - start <= 24_000 && offsets.stream().anyMatch(offset -> offset > start && offset < end)) {
				add(result, Kind.REQUESTS, "function " + name,
						"Named function lexically contains a request call. Callers, execution and runtime data flow are not established.",
						"Read this request wrapper and its actual callers. Resolve arguments, headers, signing/encryption and response handling "
								+ "with source evidence; do not infer caller edges from containment alone.",
						source, text, start, end, truncated);
			}
		}
	}

	private static void pages(FrameworkAnalysis result, String source, String text, List<Token> tokens, boolean truncated) {
		for (int i = 0; i + 2 < tokens.size(); i++) {
			Token key = tokens.get(i);
			if ((key.kind == 'i' || key.kind == 's') && Set.of("pages", "subPackages", "subpackages", "entryPagePath").contains(key.text)
					&& JsApiExtractor.at(tokens, i + 1, ":")) {
				Token value = tokens.get(i + 2);
				if (key.text.equals("entryPagePath") && value.kind == 's') {
					addPage(result, source, text, value.text, key.offset, tokenEnd(tokens, i + 2, text), truncated);
				} else if (value.text.equals("[") && value.end > i + 2) {
					pageArray(result, source, text, tokens, i + 2, value.end, "", truncated, 0);
					i = value.end;
				}
			}
		}
	}

	private static void pageArray(FrameworkAnalysis result, String source, String text, List<Token> tokens, int start, int end,
			String root, boolean truncated, int depth) {
		if (depth > 1) {
			return;
		}
		for (Range range : JsApiExtractor.split(tokens, start + 1, end)) {
			Token first = tokens.get(range.start);
			if (range.end == range.start + 1 && first.kind == 's') {
				addPage(result, source, text, root + first.text, first.offset, tokenEnd(tokens, range.end - 1, text), truncated);
				continue;
			}
			Map<String, Range> object = JsApiExtractor.object(tokens, range);
			if (object == null) {
				result.limit(Kind.PAGES, "dynamic_page_configuration");
				continue;
			}
			String path = JsApiExtractor.literal(tokens, object.get("path"));
			if (!path.isEmpty()) {
				addPage(result, source, text, root + path, first.offset, tokenEnd(tokens, range.end - 1, text), truncated);
			}
			Range child = object.get("pages");
			String prefix = JsApiExtractor.literal(tokens, object.get("root"));
			if (child != null && !prefix.isEmpty() && JsApiExtractor.at(tokens, child.start, "[")
					&& tokens.get(child.start).end == child.end - 1) {
				pageArray(result, source, text, tokens, child.start, child.end - 1, prefix + '/', truncated, depth + 1);
			}
		}
	}

	private static void addPage(FrameworkAnalysis result, String source, String text, String page, int start, int end, boolean truncated) {
		add(result, Kind.PAGES, page, "Configured page entry; no invocation or reachability is implied.",
				"Find the implementation of this configured page and follow its actual events into request code. "
						+ "Keep configuration evidence separate from confirmed calls.",
				source, text, start, end, truncated);
	}

	private static void calls(FrameworkAnalysis result, String source, String text, List<Token> tokens, boolean truncated) {
		for (int i = 0; i + 3 < tokens.size(); i++) {
			Token receiver = tokens.get(i);
			if (receiver.kind != 'i' || JsApiExtractor.at(tokens, i - 1, ".") || !JsApiExtractor.at(tokens, i + 1, ".")) {
				continue;
			}
			String member = tokens.get(i + 2).text;
			int open = i + 3;
			Kind kind;
			String task;
			if (receiver.text.equals("uni") && STORAGE_CALLS.contains(member)
					|| Set.of("AsyncStorage", "EncryptedStorage", "SecureStore", "localStorage", "sessionStorage").contains(receiver.text)
							&& Set.of("getItem", "setItem", "removeItem", "multiGet", "multiSet", "getItemAsync", "setItemAsync")
									.contains(member)) {
				kind = Kind.AUTH_STORAGE;
				task = "Inspect this storage operation and its actual callers. Determine whether the key holds authentication data "
						+ "from source; storage alone does not establish a secret or a vulnerability.";
			} else if (receiver.text.equals("uni") && member.equals("addInterceptor")) {
				kind = Kind.INTERCEPTORS;
				task = "Inspect this interceptor registration and callback implementation for request headers, signing/encryption "
						+ "and response transformation. Trace only source-supported edges.";
			} else if (receiver.text.equals("axios") && member.equals("create")) {
				kind = Kind.REQUESTS;
				task = "Read this request client configuration, including baseURL and headers. Locate references to the returned instance "
						+ "and verify its calls in source; instance calls have not been statically traced here.";
			} else if (receiver.text.equals("uni") && member.equals("requireNativePlugin") || receiver.text.equals("plus")
					|| receiver.text.equals("cordova") && member.equals("exec")
					|| receiver.text.equals("TurboModuleRegistry") && Set.of("get", "getEnforcing").contains(member)
					|| receiver.text.equals("NativeModules") || receiver.text.equals("Capacitor") && member.equals("Plugins")) {
				kind = Kind.NATIVE_BRIDGE;
				task = "Identify the native plugin/bridge invoked here and look for its registered Java/JNI/ELF entry. "
						+ "Do not claim a native implementation was decompiled or matched without evidence.";
				int depth = 0;
				while (JsApiExtractor.at(tokens, open, ".") && open + 2 < tokens.size() && depth++ < 3) {
					member += '.' + tokens.get(open + 1).text;
					open += 2;
				}
			} else if (receiver.text.equals("uni") && PAGE_CALLS.contains(member)) {
				kind = Kind.PAGES;
				task = "Read this navigation call and resolve its literal target or state why the runtime target remains unknown.";
			} else {
				continue;
			}
			if (!JsApiExtractor.at(tokens, open, "(") || tokens.get(open).end <= open) {
				continue;
			}
			int close = tokens.get(open).end;
			List<Range> args = JsApiExtractor.split(tokens, open + 1, close);
			String argument = args.isEmpty() ? "" : JsApiExtractor.literal(tokens, args.get(0));
			String title = receiver.text + '.' + member + (argument.isEmpty() ? "" : "(\"" + argument + "\")");
			add(result, kind, title,
					"Observed source call; runtime arguments, callback execution and cross-language links remain unverified.",
					task, source, text, receiver.offset, tokens.get(close).offset + 1, truncated);
		}
	}

	static void add(FrameworkAnalysis result, Kind kind, String title, String summary, String task,
			String source, String text, int start, int end, boolean truncated) {
		if (!result.hasRoom(kind)) {
			result.gap("finding_budget");
			result.limit(kind, "finding_budget");
			return;
		}
		int bounded = Math.min(Math.min(text.length(), end), start + 2400);
		String code = text.substring(start, bounded);
		int line = 1;
		for (int i = 0; i < start; i++) {
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
		AiEvidence evidence = new AiEvidence(null, source + ':' + line + ':' + start, code, AiEvidence.fingerprint(code),
				truncated || bounded < end);
		result.add(new Finding(kind, title.length() > 240 ? title.substring(0, 240) : title, summary, task, evidence));
	}

	private static int tokenEnd(List<Token> tokens, int index, String text) {
		return index + 1 < tokens.size() ? tokens.get(index + 1).offset : text.length();
	}

	static String sourceName(ResourceFile resource, List<Path> inputs) {
		return sourceName(resource, archiveLabels(inputs));
	}

	private static String sourceName(ResourceFile resource, Map<Path, String> archives) {
		if (resource.getZipEntry() != null && resource.getZipEntry().getZipFile() != null) {
			Path path = resource.getZipEntry().getZipFile().toPath().toAbsolutePath().normalize();
			String archive = archives.get(path);
			if (archive != null) {
				return display(archive + "!/" + display(resource.getZipEntry().getName().replace('\\', '/')));
			}
		}
		return display(resource.getOriginalName().replace('\\', '/'));
	}

	private static Map<Path, String> archiveLabels(List<Path> inputs) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<Path, String> result = new LinkedHashMap<>();
		for (Path input : inputs) {
			String base = display(input.getFileName() == null ? "input" : input.getFileName().toString());
			int occurrence = counts.merge(base, 1, Integer::sum);
			result.putIfAbsent(input.toAbsolutePath().normalize(), occurrence == 1 ? base : base + " [" + occurrence + ']');
		}
		return result;
	}

	private static String display(String text) {
		String clean = text.replaceAll("[\\p{Cntrl}]", " ");
		return clean.substring(0, Math.min(clean.length(), 768));
	}

	private static String normalize(String value) {
		return value.replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	private static String basename(String name) {
		return name.substring(name.lastIndexOf('/') + 1);
	}

	private static final class Read {
		private final String text;
		private final boolean truncated;
		private final int bytes;
		private final String hermes;
		private final boolean textFormat;

		private Read(String text, boolean truncated, int bytes, String hermes, boolean textFormat) {
			this.text = text;
			this.truncated = truncated;
			this.bytes = bytes;
			this.hermes = hermes;
			this.textFormat = textFormat;
		}
	}
}
