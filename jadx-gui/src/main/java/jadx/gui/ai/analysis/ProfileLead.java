package jadx.gui.ai.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jadx.gui.ai.AiCodeChunks;
import jadx.gui.ai.AiEvidence;

/**
 * Source-backed K leads; artifact identities are never fabricated Java nodes or executable code.
 */
public final class ProfileLead {
	static final String BOUNDARY = "Static APK file/header/symbol observations only. File names are indicators, not runtime proof. "
			+ "No unpacking, native disassembly/decompilation or runtime verification has been performed. "
			+ "A JavaScript asset name does not expose its source or prove its behavior. "
			+ "No matched protection marker does not establish absence of protection.";
	private static final int MAX_TEXT = 6000;
	private static final int NATIVE_START = 1000;
	private static final int SCRIPT_START = 2000;
	private static final int FRAMEWORK_START = 3000;

	private ProfileLead() {
	}

	static boolean hasEvidence(ApkProfile profile) {
		return profile.getFacts().stream().anyMatch(f -> !f.getEvidence().isEmpty())
				|| profile.getNativeLibraries().stream().anyMatch(n -> !n.getEvidence().isEmpty());
	}

	/** IDs derive from sealed profile list positions, not filtering or the user's selected goal. */
	public static List<AnalysisFinding> candidates(AnalysisReport report) {
		ApkProfile profile = report.getProfile();
		Deque<AnalysisFinding> protection = new ArrayDeque<>();
		Deque<AnalysisFinding> technology = new ArrayDeque<>();
		Deque<AnalysisFinding> visibility = new ArrayDeque<>();
		Deque<AnalysisFinding> nativeCode = new ArrayDeque<>();
		Deque<AnalysisFinding> scripts = new ArrayDeque<>();
		for (int i = 0; i < Math.min(profile.getFacts().size(), NATIVE_START - 1); i++) {
			ApkProfile.Fact fact = profile.getFacts().get(i);
			AnalysisFinding finding = fact(fact, i);
			if (finding != null) {
				(fact.getKind() == ApkProfile.Kind.PROTECTION ? protection
						: fact.getKind() == ApkProfile.Kind.TECHNOLOGY ? technology : visibility).add(finding);
			}
		}
		for (int i = 0; i < Math.min(profile.getNativeLibraries().size(), SCRIPT_START - NATIVE_START); i++) {
			AnalysisFinding finding = nativeLead(profile.getNativeLibraries().get(i), i);
			if (finding != null) {
				nativeCode.add(finding);
			}
		}
		List<AiEvidence> resources = scripts(profile);
		for (int i = 0; i < resources.size(); i++) {
			scripts.add(script(resources.get(i), i));
		}
		List<AnalysisFinding> result = new ArrayList<>();
		List<FrameworkAnalysis.Finding> framework = report.getFrameworkAnalysis().getFindings();
		for (int i = 0; i < framework.size(); i++) {
			AnalysisFinding finding = frameworkLead(framework.get(i), i);
			if (finding != null) {
				result.add(finding);
			}
		}
		List<Deque<AnalysisFinding>> groups = List.of(protection, technology, nativeCode, scripts, visibility);
		while (groups.stream().anyMatch(group -> !group.isEmpty())) {
			for (Deque<AnalysisFinding> group : groups) {
				if (!group.isEmpty()) {
					result.add(group.removeFirst());
				}
			}
		}
		return result;
	}

	public static AnalysisFinding nativeFinding(AnalysisReport report, ApkProfile.NativeLibrary library) {
		if (report == null || library == null) {
			return null;
		}
		List<ApkProfile.NativeLibrary> libraries = report.getProfile().getNativeLibraries();
		for (int i = 0; i < Math.min(libraries.size(), SCRIPT_START - NATIVE_START); i++) {
			if (libraries.get(i).getPath().equals(library.getPath())) {
				return nativeLead(libraries.get(i), i);
			}
		}
		return null;
	}

	public static AnalysisFinding scriptFinding(AnalysisReport report, AiEvidence source) {
		if (report == null || source == null) {
			return null;
		}
		List<AiEvidence> resources = scripts(report.getProfile());
		for (int i = 0; i < resources.size(); i++) {
			if (resources.get(i).getTitle().equals(source.getTitle())) {
				return script(resources.get(i), i);
			}
		}
		return null;
	}

	public static String directionKind(AnalysisFinding finding) {
		if (finding != null && finding.getKind() == AnalysisFinding.Kind.FRAMEWORK_CODE) {
			return "framework";
		}
		if (finding == null || finding.getKind() != AnalysisFinding.Kind.APK_STRUCTURE || !finding.getId().matches("K[1-9][0-9]{0,4}")) {
			return "structure";
		}
		int id = Integer.parseInt(finding.getId().substring(1));
		return id >= SCRIPT_START ? "javascript" : id >= NATIVE_START ? "native" : "structure";
	}

	static AnalysisFinding find(AnalysisReport report, String id) {
		if (id == null || !id.matches("K[1-9][0-9]{0,4}")) {
			return null;
		}
		int index = Integer.parseInt(id.substring(1));
		ApkProfile profile = report.getProfile();
		if (index < NATIVE_START) {
			return index <= profile.getFacts().size() ? fact(profile.getFacts().get(index - 1), index - 1) : null;
		}
		if (index < SCRIPT_START) {
			index -= NATIVE_START;
			return index < profile.getNativeLibraries().size() ? nativeLead(profile.getNativeLibraries().get(index), index) : null;
		}
		if (index >= FRAMEWORK_START) {
			index -= FRAMEWORK_START;
			List<FrameworkAnalysis.Finding> framework = report.getFrameworkAnalysis().getFindings();
			return index < framework.size() ? frameworkLead(framework.get(index), index) : null;
		}
		index -= SCRIPT_START;
		List<AiEvidence> resources = scripts(profile);
		return index < resources.size() ? script(resources.get(index), index) : null;
	}

	private static AnalysisFinding fact(ApkProfile.Fact fact, int index) {
		String summary = "APK structure: " + fact.getCode() + "\nValue: " + fact.getValue() + "\nConfidence: " + fact.getConfidence();
		return lead("K" + (index + 1), fact.getCode() + " · " + fact.getValue(), summary, fact.getEvidence());
	}

	public static AnalysisFinding frameworkFinding(AnalysisReport report, FrameworkAnalysis.Finding source) {
		List<FrameworkAnalysis.Finding> findings = report.getFrameworkAnalysis().getFindings();
		for (int i = 0; i < findings.size(); i++) {
			if (findings.get(i).getId().equals(source.getId())) {
				return frameworkLead(findings.get(i), i);
			}
		}
		return null;
	}

	public static String frameworkTask(AnalysisReport report, AnalysisFinding source) {
		if (source == null || source.getKind() != AnalysisFinding.Kind.FRAMEWORK_CODE) {
			return "";
		}
		for (FrameworkAnalysis.Finding finding : report.getFrameworkAnalysis().getFindings()) {
			AnalysisFinding known = frameworkFinding(report, finding);
			if (known != null && known.getId().equals(source.getId()) && known.reviewKey().equals(source.reviewKey())) {
				return finding.getTask();
			}
		}
		return "";
	}

	private static AnalysisFinding frameworkLead(FrameworkAnalysis.Finding finding, int index) {
		if (finding.getEvidence().isEmpty()) {
			return null;
		}
		AiEvidence source = finding.getEvidence().get(0);
		String code = "Framework observation: " + finding.getTitle() + '\n' + finding.getSummary()
				+ "\nContinue from here: " + finding.getTask() + "\n\nOriginal source excerpt:\n" + source.getCode();
		AnalysisFinding lead = new AnalysisFinding("K" + (FRAMEWORK_START + index), AnalysisFinding.Kind.FRAMEWORK_CODE,
				finding.getTitle(), new AiEvidence(source.getNode(), source.getTitle(), AiCodeChunks.prefix(code, MAX_TEXT),
						AiEvidence.fingerprint(code), source.isTruncated() || code.length() > MAX_TEXT));
		finding.getEvidence().stream().skip(1).forEach(lead::add);
		return lead;
	}

	private static AnalysisFinding nativeLead(ApkProfile.NativeLibrary library, int index) {
		String summary = "Native file: " + library.getPath() + "\nELF: " + library.isElf() + "\nABI: " + library.getAbi()
				+ "\nArchitecture: " + library.getArchitecture() + "\nBits: " + library.getBitness()
				+ "\nJNI exports (up to 20): " + sample(library.getJniSymbols())
				+ "\nImported symbols (up to 20): " + sample(library.getImportedSymbols())
				+ "\nExported symbols (up to 20): " + sample(library.getExportedSymbols())
				+ "\nLibrary coverage limitations: " + library.getLimitations()
				+ "\nDynamic RegisterNatives targets and machine-code behavior remain unresolved.";
		return lead("K" + (NATIVE_START + index), library.getPath(), summary, library.getEvidence());
	}

	private static String sample(List<String> symbols) {
		return symbols.isEmpty()
				? "none indexed; absence not established"
				: String.join(", ", symbols.subList(0, Math.min(20, symbols.size())))
						+ (symbols.size() > 20 ? " [sample truncated]" : "");
	}

	private static AnalysisFinding script(AiEvidence evidence, int index) {
		return lead("K" + (SCRIPT_START + index), evidence.getTitle(),
				"JavaScript/bundle artifact: " + evidence.getTitle() + "\nPresence only; inspect extracted API declarations separately.",
				List.of(evidence));
	}

	private static List<AiEvidence> scripts(ApkProfile profile) {
		Map<String, AiEvidence> resources = new LinkedHashMap<>();
		profile.getFacts().stream().filter(f -> f.getCode().equals("visibility_javascript"))
				.flatMap(f -> f.getEvidence().stream()).limit(128)
				.forEach(e -> resources.putIfAbsent(e.getTitle(), e));
		return new ArrayList<>(resources.values());
	}

	private static AnalysisFinding lead(String id, String value, String summary, List<AiEvidence> sources) {
		if (sources.isEmpty()) {
			return null;
		}
		StringBuilder text = new StringBuilder(BOUNDARY).append("\n\n").append(summary).append("\n\nObserved sources:\n");
		boolean truncated = false;
		for (AiEvidence source : sources) {
			truncated |= source.isTruncated();
			text.append(source.getTitle()).append('\n').append(source.getCode()).append("\n\n");
			if (text.length() > MAX_TEXT) {
				truncated = true;
				break;
			}
		}
		String code = AiCodeChunks.prefix(text.toString(), MAX_TEXT);
		AiEvidence source = new AiEvidence(null, sources.get(0).getTitle(), code, AiEvidence.fingerprint(code), truncated);
		return new AnalysisFinding(id, AnalysisFinding.Kind.APK_STRUCTURE, value, source);
	}
}
