package jadx.gui.ai.analysis;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiCodeChunks;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;

/** Cheap evidence rules. A keyword by itself is never promoted to a leaked credential. */
public final class AnalysisRules {
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
	private static final Pattern COMPONENT =
			Pattern.compile("(?s)<(?:activity|service|receiver|provider)\\b[^>]{0,4096}android:exported\\s*=\\s*[\"']true[\"'][^>]*>");
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
			String code = "Resource filename reference in method: " + title + "\nResource: "
					+ finding.getEvidence().get(0).getTitle() + "\nThis string match alone does not prove a read or runtime data flow.";
			finding.add(new AiEvidence(ref, title + " [resource reference]", code, AiEvidence.fingerprint(code), false));
		}
	}

	public void scan(String text, String title, JadxNodeRef ref) {
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
			add(Kind.PUBLIC_CONFIG, title, title, ref, "Firebase client configuration identifier present; value redacted.");
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
		if (calls.contains("javax/crypto/") || calls.contains("java/security/Signature") || calls.contains("SecretKeySpec")) {
			add(Kind.CRYPTO, title, title, ref, calls + '\n' + literals);
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
		String masked = kind.group().equals("secret") && !raw.isEmpty() ? code.replace(raw, "[redacted]") : code;
		if (kind == Kind.HTTP_ROUTE) {
			masked = AnalysisRedactor.safeUrl(masked);
		}
		String redacted = AiCodeChunks.prefix(AnalysisRedactor.redact(masked), 1800);
		AiEvidence evidence = new AiEvidence(ref, title, redacted, AiEvidence.fingerprint(code), code.length() > 1800);
		AnalysisFinding existing = findings.get(identity);
		if (existing != null) {
			existing.add(evidence);
			return;
		}
		if (counts.getOrDefault(kind, 0) >= 150 || findings.size() >= 1000) {
			report.gap(AiText.str("analysis.finding_limit"));
			return;
		}
		AnalysisFinding finding = new AnalysisFinding("F" + (findings.size() + 1), kind, raw, evidence);
		findings.put(identity, finding);
		counts.merge(kind, 1, Integer::sum);
		report.add(finding);
	}
}
