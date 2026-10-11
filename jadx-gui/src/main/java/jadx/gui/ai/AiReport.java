package jadx.gui.ai;

import java.time.Instant;
import java.util.Locale;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Export original Markdown and captured evidence, never HTML converted back to text. */
public final class AiReport {
	private AiReport() {
	}

	public static String html(AiRecord record) {
		String body = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()
				.render(Parser.builder().build().parse(markdown(record))).replaceAll("(?is)<img\\b[^>]*>", "");
		return "<!doctype html><html><head><meta charset='utf-8'><title>JADX AI analysis</title>"
				+ "<style>body{max-width:960px;margin:40px auto;padding:0 24px;font:16px/1.6 sans-serif;color:#263242;}"
				+ "pre{padding:16px;background:#f0f3f7;overflow:auto;}code{font-family:monospace}a{color:#366acf}</style>"
				+ "</head><body>" + body + "</body></html>";
	}

	public static String markdown(AiRecord record) {
		StringBuilder out = new StringBuilder("# ").append(record).append("\n\n")
				.append(Instant.ofEpochMilli(record.getCreatedAt())).append("\n\n");
		if (!record.getProviderSummary().isEmpty()) {
			out.append(record.getProviderSummary()).append("\n\n");
		}
		out.append(record.getNarrative()).append("\n\n---\n\n");
		if (!record.getFindings().isEmpty()) {
			out.append("## ").append(AiText.str("review_report")).append("\n\n");
			for (AiFinding finding : record.getFindings()) {
				out.append("### ").append(finding.getTitle()).append("\n\n")
						.append(AiText.str("finding_" + finding.getKind().name().toLowerCase(Locale.ROOT))).append(" · ")
						.append(AiText.str("model_claim")).append("\n\n")
						.append(finding.getDetail()).append("\n\n")
						.append("**").append(AiText.str("finding_gaps")).append(":** ").append(finding.getGaps()).append("\n\n")
						.append("**").append(AiText.str("finding_verification")).append(":** ").append(finding.getVerification())
						.append("\n\n")
						.append("**").append(AiText.str("human_review")).append(":** ")
						.append(AiText.str("review_" + finding.getReview().name().toLowerCase(Locale.ROOT))).append("\n\n");
				if (finding.getReviewedAt() != 0) {
					out.append(Instant.ofEpochMilli(finding.getReviewedAt())).append("\n\n");
				}
				if (!finding.getReviewNote().isEmpty()) {
					out.append(AiText.str("review_note")).append(":\n\n");
					fenced(out, "text", finding.getReviewNote());
				}
				for (AiFinding.Citation citation : finding.getCitations()) {
					out.append(citation).append(" · ")
							.append(AiText.str(citation.matches(record.getEvidence()) ? "quote_found" : "quote_missing")).append("\n\n");
					fenced(out, "java", citation.getQuote());
				}
			}
		}
		for (int i = 0; i < record.getEvidence().size(); i++) {
			AiEvidence source = record.getEvidence().get(i);
			out.append("## [S").append(i + 1).append("] ").append(source.getTitle()).append("\n\n")
					.append("Class snapshot SHA-256: ").append(source.getFingerprint()).append("\n\n");
			if (source.isTruncated()) {
				out.append(AiText.str("truncated")).append("\n\n");
			}
			fenced(out, "java", source.getCode());
		}
		if (!record.getFindings().isEmpty()) {
			out.append("## ").append(AiText.str("raw_response")).append("\n\n");
			fenced(out, "text", record.getAnswer());
		}
		return out.toString();
	}

	private static void fenced(StringBuilder out, String language, String text) {
		String fence = "```";
		while (text.contains(fence)) {
			fence += '`';
		}
		out.append(fence).append(language).append('\n').append(text).append('\n').append(fence).append("\n\n");
	}
}
