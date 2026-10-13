package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/**
 * Bounded local observations of the supplied archives, not an unpacking or vulnerability verdict.
 */
public final class ApkProfile {

	public enum Kind {
		PROTECTION, TECHNOLOGY, VISIBILITY
	}

	public enum Confidence {
		OBSERVED, SUSPECTED, UNKNOWN
	}

	public static final class Fact {
		private Kind kind;
		private String code;
		private String value;
		private Confidence confidence;
		private List<AiEvidence> evidence = new ArrayList<>();

		private Fact() {
		}

		Fact(Kind kind, String code, String value, Confidence confidence, List<AiEvidence> evidence) {
			this.kind = kind;
			this.code = code;
			this.value = value;
			this.confidence = confidence;
			this.evidence = new ArrayList<>(evidence);
		}

		public Kind getKind() {
			return kind;
		}

		public String getCode() {
			return code;
		}

		public String getValue() {
			return value;
		}

		public Confidence getConfidence() {
			return confidence;
		}

		public List<AiEvidence> getEvidence() {
			return Collections.unmodifiableList(evidence);
		}
	}

	/** ELF symbol information is only an index; no machine code is decompiled. */
	public static final class NativeLibrary {
		private String path;
		private String abi;
		private String architecture = "unknown";
		private int bitness;
		private boolean elf;
		private List<String> importedSymbols = new ArrayList<>();
		private List<String> exportedSymbols = new ArrayList<>();
		private List<String> jniSymbols = new ArrayList<>();
		private List<AiEvidence> evidence = new ArrayList<>();
		private List<String> limitations = new ArrayList<>();

		private NativeLibrary() {
		}

		NativeLibrary(String path, String abi) {
			this.path = path;
			this.abi = abi;
		}

		public String getPath() {
			return path;
		}

		public String getAbi() {
			return abi;
		}

		public String getArchitecture() {
			return architecture;
		}

		public int getBitness() {
			return bitness;
		}

		public boolean isElf() {
			return elf;
		}

		public List<String> getImportedSymbols() {
			return Collections.unmodifiableList(importedSymbols);
		}

		public List<String> getExportedSymbols() {
			return Collections.unmodifiableList(exportedSymbols);
		}

		public List<String> getJniSymbols() {
			return Collections.unmodifiableList(jniSymbols);
		}

		public List<AiEvidence> getEvidence() {
			return Collections.unmodifiableList(evidence);
		}

		public List<String> getLimitations() {
			return Collections.unmodifiableList(limitations);
		}

		void header(int bits, String arch) {
			elf = true;
			bitness = bits;
			architecture = arch;
		}

		void symbol(String name, boolean imported) {
			List<String> target = imported ? importedSymbols : exportedSymbols;
			if (!target.contains(name)) {
				if (target.size() < 128) {
					target.add(name);
				} else {
					limit("elf_symbol_limit");
				}
			}
			if (!imported && (name.startsWith("Java_") || name.equals("JNI_OnLoad") || name.equals("JNI_OnUnload"))
					&& jniSymbols.size() < 128 && !jniSymbols.contains(name)) {
				jniSymbols.add(name);
			}
		}

		void limit(String code) {
			if (!limitations.contains(code)) {
				limitations.add(code);
			}
		}

		void evidence(AiEvidence value) {
			evidence.add(value);
		}
	}

	private List<Fact> facts = new ArrayList<>();
	private List<NativeLibrary> nativeLibraries = new ArrayList<>();
	private List<String> gaps = new ArrayList<>();
	private List<String> recommendations = new ArrayList<>();
	private int dexCount;
	private int javaScriptCount;
	private int nativeLibraryCount;
	private int archiveCount;
	private boolean complete;

	public ApkProfile() {
	}

	public List<Fact> getFacts() {
		return Collections.unmodifiableList(facts);
	}

	public List<NativeLibrary> getNativeLibraries() {
		return Collections.unmodifiableList(nativeLibraries);
	}

	public List<String> getGaps() {
		return Collections.unmodifiableList(gaps);
	}

	public List<String> getRecommendations() {
		return Collections.unmodifiableList(recommendations);
	}

	public int getDexCount() {
		return dexCount;
	}

	public int getJavaScriptCount() {
		return javaScriptCount;
	}

	public int getNativeLibraryCount() {
		return nativeLibraryCount;
	}

	public int getArchiveCount() {
		return archiveCount;
	}

	/**
	 * Inventory completed within its budgets; individual libraries may still have read/symbol
	 * limitations.
	 */
	public boolean isComplete() {
		return complete;
	}

	public List<AiEvidence> getEvidence() {
		return java.util.stream.Stream.concat(facts.stream().flatMap(fact -> fact.getEvidence().stream()),
				nativeLibraries.stream().flatMap(library -> library.getEvidence().stream()))
				.distinct().collect(java.util.stream.Collectors.toList());
	}

	void fact(Kind kind, String code, String value, Confidence confidence, List<AiEvidence> evidence) {
		facts.add(new Fact(kind, code, value, confidence, evidence));
	}

	void gap(String code, String location) {
		if (gaps.size() < 50) {
			gaps.add(code + (location.isEmpty() ? "" : ": " + location));
		}
	}

	void recommend(String code) {
		if (!recommendations.contains(code)) {
			recommendations.add(code);
		}
	}

	void dex() {
		dexCount++;
	}

	void javaScript() {
		javaScriptCount++;
	}

	void nativeLibrary() {
		nativeLibraryCount++;
	}

	void archive() {
		archiveCount++;
	}

	void addNative(NativeLibrary library) {
		nativeLibraries.add(library);
	}

	void complete() {
		complete = gaps.isEmpty();
	}

	/** Scanner never mutates facts or libraries after publishing this value. */
	public ApkProfile snapshot() {
		ApkProfile copy = new ApkProfile();
		copy.facts = new ArrayList<>(facts);
		copy.nativeLibraries = new ArrayList<>(nativeLibraries);
		copy.gaps = new ArrayList<>(gaps);
		copy.recommendations = new ArrayList<>(recommendations);
		copy.dexCount = dexCount;
		copy.javaScriptCount = javaScriptCount;
		copy.nativeLibraryCount = nativeLibraryCount;
		copy.archiveCount = archiveCount;
		copy.complete = complete;
		return copy;
	}

	public String markdown() {
		if (facts.isEmpty()) {
			return "";
		}
		StringBuilder text = new StringBuilder("\n## " + AiText.str("profile.title") + "\n\n");
		for (Fact fact : facts) {
			text.append("- ").append(AiText.str("profile.fact." + fact.code)).append(": ")
					.append(fact.value).append(" · ").append(fact.confidence).append('\n');
			for (AiEvidence source : fact.evidence) {
				text.append("\n````text\n").append(safe(source.getTitle())).append('\n')
						.append(safe(source.getCode())).append("\n````\n");
			}
		}
		for (NativeLibrary library : nativeLibraries) {
			text.append("\n### ").append(safe(library.path)).append("\n\n````text\n")
					.append(library.abi).append(" / ").append(library.architecture).append(" / ").append(library.bitness).append(" bit\n")
					.append("JNI: ").append(safe(library.jniSymbols.toString())).append('\n')
					.append("Imports: ").append(safe(library.importedSymbols.toString())).append('\n')
					.append("Exports: ").append(safe(library.exportedSymbols.toString())).append('\n');
			for (String limitation : library.limitations) {
				text.append(AiText.str("profile.limitation." + limitation)).append('\n');
			}
			text.append("````\n");
		}
		for (String gap : gaps) {
			text.append("\n- ").append(safe(gap)).append('\n');
		}
		return text.toString();
	}

	private static String safe(String value) {
		return value.replace("````", "` ` ` `").replace('\r', ' ');
	}
}
