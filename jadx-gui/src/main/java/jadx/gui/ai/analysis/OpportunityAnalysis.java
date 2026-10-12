package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/** Source-bound AI triage. Quote checks establish provenance, never exploitability. */
public final class OpportunityAnalysis {
	public enum Priority {
		FIRST, INVESTIGATE, DEFER
	}

	public enum Access {
		NO_APP_LOGIN, ACCOUNT, DEVICE, UNKNOWN
	}

	public enum Effort {
		LOW, MEDIUM, HIGH, UNKNOWN
	}

	public enum Protection {
		NONE_IN_SCOPE, PRESENT, UNKNOWN
	}

	public enum Impact {
		LIMITED, SENSITIVE, CONTROL, UNKNOWN
	}

	public static final class Citation {
		private String source;
		private String quote;

		private Citation() {
		}

		Citation(String source, String quote) {
			this.source = source;
			this.quote = quote;
		}

		public String getSource() {
			return source;
		}

		public String getQuote() {
			return quote;
		}

		public AiEvidence resolve(List<AiEvidence> evidence) {
			if (source == null || !source.matches("S[1-9][0-9]{0,3}")) {
				return null;
			}
			int index = Integer.parseInt(source.substring(1)) - 1;
			return index < evidence.size() ? evidence.get(index) : null;
		}

		@Override
		public String toString() {
			return "[" + source + "]";
		}
	}

	public static final class Candidate {
		private String leadId;
		private String title;
		private Priority requestedPriority;
		private Priority priority;
		private Access access;
		private Effort effort;
		private Protection protection;
		private Impact impact;
		private String prerequisites;
		private String guards;
		private String impactDetail;
		private String rationale;
		private String gaps;
		private String next;
		private List<Citation> citations = new ArrayList<>();
		private List<String> limitations = new ArrayList<>();

		private Candidate() {
		}

		public String getLeadId() {
			return leadId;
		}

		public String getTitle() {
			return title;
		}

		public Priority getPriority() {
			return priority;
		}

		public Priority getRequestedPriority() {
			return requestedPriority;
		}

		public Access getAccess() {
			return access;
		}

		public Effort getEffort() {
			return effort;
		}

		public Impact getImpact() {
			return impact;
		}

		public Protection getProtection() {
			return protection;
		}

		public String getRationale() {
			return rationale;
		}

		public List<Citation> getCitations() {
			return Collections.unmodifiableList(citations);
		}

		public List<String> getLimitations() {
			return Collections.unmodifiableList(limitations);
		}

		public String detail() {
			StringBuilder out = new StringBuilder(leadId + " · " + title + "\n\n");
			out.append(label(priority)).append(" · ").append(AiText.str("opportunity.inference")).append("\n\n");
			section(out, "why", rationale);
			section(out, "conditions", label(access) + " · " + label(effort) + "\n" + prerequisites);
			section(out, "guards", label(protection) + "\n" + guards);
			section(out, "impact", label(impact) + "\n" + impactDetail);
			section(out, "next", next);
			section(out, "gaps", gaps);
			if (!limitations.isEmpty()) {
				section(out, "gate", limitations.stream().map(k -> AiText.str("opportunity.limit." + k))
						.collect(java.util.stream.Collectors.joining("\n")));
			}
			out.append(AiText.str("opportunity.quotes")).append("\n\n");
			for (Citation citation : citations) {
				out.append(citation).append('\n').append(citation.quote).append("\n\n");
			}
			return out.toString();
		}
	}

	private static final Pattern BLOCK = Pattern.compile("(?m)^```jadx-opportunities[ \\t]*\\r?\\n([\\s\\S]*?)^```[ \\t]*(?:\\r?\\n|$)");
	private List<Candidate> candidates = new ArrayList<>();
	private List<String> selectedIds = new ArrayList<>();
	private int catalogSize;
	private int rejected;
	private boolean parsed;

	private OpportunityAnalysis() {
	}

	public List<Candidate> getCandidates() {
		return Collections.unmodifiableList(candidates);
	}

	public List<String> getSelectedIds() {
		return Collections.unmodifiableList(selectedIds);
	}

	public int getCatalogSize() {
		return catalogSize;
	}

	public int getRejected() {
		return rejected;
	}

	public boolean isParsed() {
		return parsed;
	}

	public String scope() {
		return String.format(AiText.str("opportunity.scope"), selectedIds.size(), catalogSize)
				+ "\n" + AiText.str("opportunity.boundary");
	}

	public String diagnostics() {
		if (!parsed) {
			return AiText.str("opportunity.invalid");
		}
		String empty = candidates.isEmpty() ? AiText.str("opportunity.none") : "";
		return rejected == 0 ? empty : empty + "\n" + String.format(AiText.str("opportunity.rejected"), rejected);
	}

	public String narrative(String answer) {
		return parsed && !(candidates.isEmpty() && rejected > 0) ? BLOCK.matcher(answer).replaceFirst("").strip() : answer;
	}

	public String markdown() {
		StringBuilder out = new StringBuilder("## " + AiText.str("opportunity.title") + "\n\n" + scope() + "\n\n");
		if (!diagnostics().isEmpty()) {
			out.append(diagnostics()).append("\n\n");
		}
		for (Candidate candidate : candidates) {
			// Treat all model values as data, including Markdown, links, and fences.
			String detail = candidate.detail();
			String fence = "```";
			while (detail.contains(fence)) {
				fence += '`';
			}
			out.append(fence).append("text\n").append(detail).append(fence).append("\n\n");
		}
		return out.toString();
	}

	public static String label(Enum<?> value) {
		return AiText.str("opportunity." + value.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT)
				+ "." + value.name().toLowerCase(Locale.ROOT));
	}

	private static void section(StringBuilder out, String key, String value) {
		out.append(AiText.str("opportunity." + key)).append('\n').append(value).append("\n\n");
	}

	public static String instructions() {
		return "\nCompare ONLY the selected leads and their supplied sources for practical next investigation steps. "
				+ "Explain access prerequisites, effort, existing protections, possible impact, counterevidence and what to check next. "
				+ "Prioritize concrete reachable operations supported by code over URL strings or generic keyword hits. "
				+ "NO_APP_LOGIN describes a client entry only; it never establishes missing server authentication. "
				+ "NONE_IN_SCOPE means no guard observed in the supplied slice, not proof of absent protection. "
				+ "A call reference does not prove data flow; client code cannot prove a server authorization flaw. "
				+ "Do not invent full URLs, credentials, runtime checks, success rates or confirmed vulnerabilities. "
				+ "FIRST requires known access, LOW effort, known impact and quoted code that supports the reasoning; "
				+ "otherwise INVESTIGATE; use DEFER for costly or guarded leads. Cite support for all factors, and state uncertainty. "
				+ "After a concise readable comparison, append exactly one fenced jadx-opportunities JSON block: "
				+ "{\"version\":1,\"candidates\":[{\"leadId\":\"F1\",\"title\":\"short entry/operation\","
				+ "\"priority\":\"FIRST|INVESTIGATE|DEFER\",\"access\":\"NO_APP_LOGIN|ACCOUNT|DEVICE|UNKNOWN\","
				+ "\"effort\":\"LOW|MEDIUM|HIGH|UNKNOWN\",\"protection\":\"NONE_IN_SCOPE|PRESENT|UNKNOWN\","
				+ "\"impact\":\"LIMITED|SENSITIVE|CONTROL|UNKNOWN\",\"prerequisites\":\"conditions and effort basis\","
				+ "\"guards\":\"observed checks and their scope\",\"impactDetail\":\"conditional impact\","
				+ "\"rationale\":\"why before/after the other selected leads\",\"gaps\":\"unknowns and possible refutation\","
				+ "\"next\":\"concrete next check and expected observation\","
				+ "\"citations\":[{\"source\":\"S1\",\"quote\":\"exact contiguous supplied substring\"}]}]}. "
				+ "Use user's language for text. At most 6 unique selected lead IDs, title <=160 characters, "
				+ "each descriptive field <=1200, 1-4 citations each with a nonblank quote <=600. "
				+ "A citation MUST belong to that lead in the source map; copy whitespace exactly. "
				+ "Return an empty candidates array if evidence cannot support useful leads. Never set human review fields.\n";
	}

	/**
	 * Ownership and code-source classification are supplied by the local collector, never by the model.
	 */
	static OpportunityAnalysis parse(String answer, List<AnalysisFinding> selected, int catalogSize, List<AiEvidence> evidence,
			Map<String, Set<String>> ownership, Set<String> codeSources) {
		OpportunityAnalysis analysis = new OpportunityAnalysis();
		selected.forEach(f -> analysis.selectedIds.add(f.getId()));
		analysis.catalogSize = catalogSize;
		if (answer.length() > 160_000) {
			return analysis;
		}
		Matcher matcher = BLOCK.matcher(answer);
		if (!matcher.find()) {
			return analysis;
		}
		String json = matcher.group(1);
		if (matcher.find() || json.length() > 64_000 || !boundedDepth(json)) {
			return analysis;
		}
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			if (!root.get("version").isJsonPrimitive() || !root.get("version").getAsJsonPrimitive().isNumber()
					|| !root.get("version").getAsString().equals("1") || root.getAsJsonArray("candidates").size() > 6) {
				return analysis;
			}
			Set<String> seen = new java.util.HashSet<>();
			for (JsonElement element : root.getAsJsonArray("candidates")) {
				try {
					JsonObject obj = element.getAsJsonObject();
					Candidate c = new Candidate();
					c.leadId = string(obj, "leadId", 10);
					AnalysisFinding lead = selected.stream().filter(f -> f.getId().equals(c.leadId)).findFirst().orElseThrow();
					if (seen.contains(c.leadId)) {
						throw new IllegalArgumentException();
					}
					c.title = string(obj, "title", 160);
					c.requestedPriority = Priority.valueOf(string(obj, "priority", 20));
					c.priority = c.requestedPriority;
					c.access = Access.valueOf(string(obj, "access", 20));
					c.effort = Effort.valueOf(string(obj, "effort", 20));
					c.protection = Protection.valueOf(string(obj, "protection", 20));
					c.impact = Impact.valueOf(string(obj, "impact", 20));
					c.prerequisites = string(obj, "prerequisites", 1200);
					c.guards = string(obj, "guards", 1200);
					c.impactDetail = string(obj, "impactDetail", 1200);
					c.rationale = string(obj, "rationale", 1200);
					c.gaps = string(obj, "gaps", 1200);
					c.next = string(obj, "next", 1200);
					if (obj.getAsJsonArray("citations").isEmpty() || obj.getAsJsonArray("citations").size() > 4) {
						throw new IllegalArgumentException();
					}
					for (JsonElement ref : obj.getAsJsonArray("citations")) {
						Citation citation =
								new Citation(string(ref.getAsJsonObject(), "source", 10), string(ref.getAsJsonObject(), "quote", 600));
						AiEvidence source = citation.resolve(evidence);
						if (!ownership.getOrDefault(c.leadId, Set.of()).contains(citation.source)
								|| source == null || !source.getCode().contains(citation.quote)) {
							throw new IllegalArgumentException();
						}
						c.citations.add(citation);
					}
					if (lead.getKind() == AnalysisFinding.Kind.ENDPOINT) {
						c.limitations.add("address");
					}
					if (c.citations.stream().noneMatch(ref -> codeSources.contains(ref.source))) {
						c.limitations.add("code");
					}
					if (c.citations.stream().anyMatch(ref -> codeSources.contains(ref.source) && ref.resolve(evidence).isTruncated())) {
						c.limitations.add("truncated");
					}
					if (c.access == Access.UNKNOWN || c.effort == Effort.UNKNOWN || c.impact == Impact.UNKNOWN
							|| c.protection == Protection.UNKNOWN) {
						c.limitations.add("unknown");
					}
					if (c.effort != Effort.LOW && c.effort != Effort.UNKNOWN) {
						c.limitations.add("effort");
					}
					if (c.protection == Protection.PRESENT) {
						c.limitations.add("guard");
					}
					if (c.priority == Priority.FIRST && !c.limitations.isEmpty()) {
						c.priority = Priority.INVESTIGATE;
					}
					seen.add(c.leadId);
					analysis.candidates.add(c);
				} catch (RuntimeException invalidCard) {
					analysis.rejected++;
				}
			}
			analysis.candidates.sort(Comparator.comparing(Candidate::getPriority));
			analysis.parsed = true;
		} catch (RuntimeException invalidBlock) {
			analysis.candidates.clear();
			analysis.rejected = 0;
		}
		return analysis;
	}

	private static String string(JsonObject object, String key, int max) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException();
		}
		String text = value.getAsString();
		if (text.isBlank() || text.length() > max) {
			throw new IllegalArgumentException();
		}
		return text;
	}

	private static boolean boundedDepth(String text) {
		int depth = 0;
		boolean quoted = false;
		boolean escaped = false;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (quoted) {
				if (escaped) {
					escaped = false;
				} else if (ch == '\\') {
					escaped = true;
				} else if (ch == '"') {
					quoted = false;
				}
			} else if (ch == '"') {
				quoted = true;
			} else if (ch == '{' || ch == '[') {
				if (++depth > 12) {
					return false;
				}
			} else if (ch == '}' || ch == ']') {
				if (--depth < 0) {
					return false;
				}
			}
		}
		return depth == 0 && !quoted;
	}
}
