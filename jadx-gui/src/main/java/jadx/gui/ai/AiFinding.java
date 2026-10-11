package jadx.gui.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Model claims and human review are deliberately separate. Quotes are checked against captured
 * source, not live code.
 */
public final class AiFinding {

	public enum Kind {
		OBSERVATION, HYPOTHESIS, QUESTION
	}

	public enum Review {
		UNREVIEWED, SUPPORTED, REJECTED, NEEDS_EVIDENCE
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
			return quote == null ? "" : quote;
		}

		public AiEvidence resolve(List<AiEvidence> evidence) {
			if (source == null || !source.matches("S[1-9][0-9]{0,3}")) {
				return null;
			}
			int index = Integer.parseInt(source.substring(1)) - 1;
			return index < evidence.size() ? evidence.get(index) : null;
		}

		public boolean matches(List<AiEvidence> evidence) {
			AiEvidence captured = resolve(evidence);
			return captured != null && !getQuote().isBlank() && captured.getCode().contains(getQuote());
		}

		@Override
		public String toString() {
			return "[" + source + "]";
		}
	}

	private Kind kind;
	private String title;
	private String detail;
	private List<Citation> citations;
	private String gaps;
	private String verification;
	private Review review = Review.UNREVIEWED;
	private String reviewNote = "";
	private long reviewedAt;

	private AiFinding() {
		// Required by project Gson with Unsafe disabled.
	}

	AiFinding(Kind kind, String title, String detail, List<Citation> citations, String gaps, String verification) {
		this.kind = kind;
		this.title = title;
		this.detail = detail;
		this.citations = new ArrayList<>(citations);
		this.gaps = gaps;
		this.verification = verification;
	}

	public Kind getKind() {
		return kind == null ? Kind.QUESTION : kind;
	}

	public String getTitle() {
		return title;
	}

	public String getDetail() {
		return detail;
	}

	public List<Citation> getCitations() {
		return citations == null ? Collections.emptyList() : Collections.unmodifiableList(citations);
	}

	public String getGaps() {
		return gaps;
	}

	public String getVerification() {
		return verification;
	}

	public Review getReview() {
		return review == null ? Review.UNREVIEWED : review;
	}

	public String getReviewNote() {
		return reviewNote == null ? "" : reviewNote;
	}

	public long getReviewedAt() {
		return reviewedAt;
	}

	public void review(Review value, String note) {
		Objects.requireNonNull(value);
		Objects.requireNonNull(note);
		if (note.length() > 4000) {
			throw new IllegalArgumentException(AiText.str("review_note_limit"));
		}
		if (value != getReview() || !note.equals(getReviewNote())) {
			review = value;
			reviewNote = note;
			reviewedAt = System.currentTimeMillis();
		}
	}
}
