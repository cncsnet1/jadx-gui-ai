package jadx.gui.ai.analysis;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;

/** Cheap evidence rules. A keyword by itself is never promoted to a leaked credential. */
public final class AnalysisRules {
	private static final int FINDINGS_PER_KIND = 150;
	private static final int FINDINGS_TOTAL = 1000;
	private static final int CODE_RESERVE_PER_KIND = 50;
	private static final int CODE_RESERVE_TOTAL = 250;
	private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>\\\\]{4,2000}", Pattern.CASE_INSENSITIVE);
	private static final Pattern KEY = Pattern.compile(
			"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----(?:[\\s\\S]{0,8000}?-----END (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----)?");
	private static final Pattern CREDENTIAL = Pattern.compile(
			"(?i)(?:[\\w.$-]*(?:password|passwd|pwd|client_secret|private_key|access_token|auth_token|api_secret)[\\w.$-]*)[\"']?\\s*[:=]\\s*[\"']([^\"'\\r\\n]{4,1024})[\"']");
	private static final Pattern XML_CREDENTIAL = Pattern.compile(
			"(?i)<string\\s+name=[\"']([^\"']*(?:password|passwd|client_secret|access_token|api_secret)[^\"']*)[\"'][^>]*>([^<]{4,1024})</string>");
	private static final Pattern PLAIN_CREDENTIAL = Pattern.compile(
			"(?im)^([\\w.-]*(?:password|passwd|client_secret|access_token|api_secret)[\\w.-]*)\\s*[:=]\\s*([^\\s#\"']{4,1024})\\s*$");
	private static final Pattern TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9_.~+/=-]{12,2048}");
	private static final Pattern PUBLIC_JSON_KEY = Pattern.compile("\"current_key\"\\s*:\\s*\"([^\"\\r\\n]{1,1024})\"");
	private static final Pattern PUBLIC_XML_KEY = Pattern.compile("<string\\s+name=[\"']google_api_key[\"'][^>]*>([^<]{1,1024})</string>");
	private static final Pattern COMPONENT =
			Pattern.compile("(?s)<(?:activity|service|receiver|provider)\\b[^>]{0,4096}android:exported\\s*=\\s*[\"']true[\"'][^>]*>");
	private static final Pattern MANIFEST_PACKAGE = Pattern.compile("<manifest\\b[^>]{0,8192}?\\bpackage\\s*=\\s*[\"']([^\"']+)[\"']");
	private final AnalysisReport report;
	private final Map<String, AnalysisFinding> findings = new HashMap<>();
	private final Map<Kind, Integer> counts = new HashMap<>();
	private final Map<String, java.util.List<AnalysisFinding>> resourceReferences = new HashMap<>();

	public AnalysisRules(AnalysisReport report) {
		this.report = report;
	}

	public void indexResourceReferences() {
		for (AnalysisFinding finding : report.getFindings()) {
			AiEvidence evidence = finding.getEvidence().get(0);
			if (evidence.getNode() != null) {
				continue;
			}
			String path = evidence.getTitle().replaceFirst(":\\d+$", "");
			resourceReferences.computeIfAbsent(path, ignored -> new java.util.ArrayList<>()).add(finding);
			int slash = path.lastIndexOf('/');
			if (slash >= 0) {
				resourceReferences.computeIfAbsent(path.substring(slash + 1), ignored -> new java.util.ArrayList<>()).add(finding);
			}
		}
	}

	public void linkResource(String literal, String title, JadxNodeRef ref) {
		for (AnalysisFinding finding : resourceReferences.getOrDefault(literal, java.util.List.of())) {
			String resourceTitle = finding.getSourceReferences().stream().filter(source -> source.getNode() == null)
					.map(AnalysisFinding.SourceReference::getTitle)
					.filter(source -> {
						String path = source.replaceFirst(":\\d+$", "");
						return path.equals(literal) || path.endsWith('/' + literal);
					}).findFirst().orElse("");
			if (resourceTitle.isEmpty()) {
				continue;
			}
			String code = "Resource filename reference in method: " + title + "\nResource: "
					+ resourceTitle + "\nThis string match alone does not prove a read or runtime data flow.";
			finding.add(new AiEvidence(ref, title + " [resource reference]", code, AiEvidence.fingerprint(code), false));
		}
	}

	public void scan(String text, String title, JadxNodeRef ref) {
		String normalizedTitle = title.replace('\\', '/');
		int archive = normalizedTitle.lastIndexOf("!/");
		boolean rootManifest = archive >= 0
				? normalizedTitle.substring(archive + 2).equals("AndroidManifest.xml")
				: normalizedTitle.equals("AndroidManifest.xml") || normalizedTitle.endsWith("/AndroidManifest.xml");
		if (ref == null && report.getAppPackage().isEmpty() && rootManifest) {
			Matcher manifest = MANIFEST_PACKAGE.matcher(text.replaceAll("(?s)<!--.*?-->", ""));
			if (manifest.find()) {
				report.setAppPackage(manifest.group(1));
			}
		}
		match(KEY, text, title, ref, Kind.PRIVATE_KEY, 0);
		match(CREDENTIAL, text, title, ref, Kind.CREDENTIAL, 1);
		match(XML_CREDENTIAL, text, title, ref, Kind.CREDENTIAL, 2);
		match(PLAIN_CREDENTIAL, text, title, ref, Kind.CREDENTIAL, 2);
		match(TOKEN, text, title, ref, Kind.TOKEN, 0);
		match(URL, text, title, ref, Kind.ENDPOINT, 0);
		if (title.contains("AndroidManifest.xml")) {
			match(COMPONENT, text, title, ref, Kind.EXPORTED, 0);
			manifestFlag(text, title, "debuggable", Kind.DEBUGGABLE);
			manifestFlag(text, title, "allowBackup", Kind.BACKUP);
		}
		if ((title.contains("google-services") || title.endsWith("strings.xml"))
				&& (text.contains("google_api_key") || text.contains("current_key"))) {
			match(PUBLIC_JSON_KEY, text, title, ref, Kind.PUBLIC_CONFIG, 1);
			match(PUBLIC_XML_KEY, text, title, ref, Kind.PUBLIC_CONFIG, 1);
		}
	}

	private void manifestFlag(String text, String title, String flag, Kind kind) {
		match(Pattern.compile("android:" + flag + "\\s*=\\s*[\"']true[\"']"), text, title, null, kind, 0);
	}

	private void match(Pattern pattern, String text, String title, JadxNodeRef ref, Kind kind, int group) {
		Matcher matcher = pattern.matcher(text);
		int matches = 0;
		while (matcher.find() && matches++ < 200) {
			String value = matcher.group(group);
			if (kind == Kind.CREDENTIAL
					&& (value.contains("[redacted]") || value.matches("(?i)(password|passwd|undefined|null|false|true|\\*+|\\$\\{.*)"))) {
				continue;
			}
			int start = Math.max(0, text.lastIndexOf('\n', matcher.start()) + 1);
			int end = text.indexOf('\n', matcher.end());
			if (end < 0) {
				end = text.length();
			}
			int line = 1;
			for (int i = 0; i < matcher.start(); i++) {
				if (text.charAt(i) == '\n') {
					line++;
				}
			}
			add(kind, value, title + ':' + line, ref, text.substring(start, end));
			if (kind == Kind.ENDPOINT) {
				report.addApi(new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "", value, title + ':' + line, ref));
			}
		}
	}

	public void behavior(String calls, String literals, String title, JadxNodeRef ref) {
		String lower = (calls + ' ' + literals).toLowerCase(Locale.ROOT);
		if (lower.contains("login") || lower.contains("signin") || lower.contains("password") || lower.contains("oauth")) {
			add(Kind.LOGIN, title, title, ref, calls + '\n' + literals);
		}
		if (calls.contains("javax/crypto/") || calls.contains("java/security/Signature") || calls.contains("SecretKeySpec")
				|| calls.contains("java/security/MessageDigest")) {
			String cryptoCalls = calls.lines().filter(call -> call.contains("javax/crypto/") || call.contains("java/security/"))
					.distinct().limit(20).collect(java.util.stream.Collectors.joining("\n"));
			add(Kind.CRYPTO, title, title, ref, cryptoCalls + '\n' + literals
					+ "\nStatic calls and constants in one method; argument binding, key/IV sources and request data flow are not established.");
		}
		if (calls.contains("DexClassLoader") || calls.contains("Runtime;.exec") || calls.contains("Runtime.exec")
				|| calls.contains("ProcessBuilder")) {
			add(Kind.DYNAMIC, title, title, ref, calls + '\n' + literals);
		}
		if (calls.contains("WebView") && (calls.contains("addJavascriptInterface") || calls.contains("loadUrl"))) {
			add(Kind.WEBVIEW, title, title, ref, calls + '\n' + literals);
		}
	}

	public void add(Kind kind, String raw, String title, JadxNodeRef ref, String code) {
		String identity = kind.name() + ':' + AiEvidence.fingerprint(raw);
		String snippet = ApiEndpoint.boundedText(code, 1800);
		AiEvidence evidence = new AiEvidence(ref, title, snippet, AiEvidence.fingerprint(code), code.length() > 1800);
		AnalysisFinding existing = findings.get(identity);
		if (existing != null) {
			existing.add(evidence, report);
			return;
		}
		boolean codeReserve = ref != null && EvidenceTriage.classRank(report, ref.getDeclaringClass()) >= 0;
		int kindLimit = FINDINGS_PER_KIND + (codeReserve ? CODE_RESERVE_PER_KIND : 0);
		int totalLimit = FINDINGS_TOTAL + (codeReserve ? CODE_RESERVE_TOTAL : 0);
		if (counts.getOrDefault(kind, 0) >= kindLimit || findings.size() >= totalLimit) {
			report.budgetOmission("finding." + (codeReserve ? "code_reserve." : "general.") + kind.name().toLowerCase(Locale.ROOT));
			report.gap(AiText.str("analysis.finding_limit"));
			return;
		}
		AnalysisFinding finding = new AnalysisFinding("F" + (findings.size() + 1), kind, raw, evidence);
		findings.put(identity, finding);
		counts.merge(kind, 1, Integer::sum);
		report.add(finding);
	}
}
