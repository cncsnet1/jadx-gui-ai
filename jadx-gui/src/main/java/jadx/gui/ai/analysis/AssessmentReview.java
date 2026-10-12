package jadx.gui.ai.analysis;

import java.util.Locale;
import java.util.Objects;

import jadx.gui.ai.AiText;

/** A human's local assessment record. Neither rules nor model responses can create one. */
public final class AssessmentReview {
	public enum Status {
		UNREVIEWED, INVESTIGATING, NEEDS_RUNTIME, REPRODUCED, DISMISSED;

		@Override
		public String toString() {
			return AiText.str("assessment.status." + name().toLowerCase(Locale.ROOT));
		}
	}

	public enum Severity {
		UNASSESSED, INFO, LOW, MEDIUM, HIGH, CRITICAL;

		@Override
		public String toString() {
			return AiText.str("assessment.severity." + name().toLowerCase(Locale.ROOT));
		}
	}

	private Status status = Status.UNREVIEWED;
	private Severity severity = Severity.UNASSESSED;
	private String note = "";
	private long updatedAt;

	private AssessmentReview() {
	}

	public AssessmentReview(Status status, Severity severity, String note) {
		this.status = Objects.requireNonNull(status);
		this.severity = Objects.requireNonNull(severity);
		this.note = Objects.requireNonNull(note).trim();
		if (this.note.length() > 4000) {
			throw new IllegalArgumentException(AiText.str("assessment.note_limit"));
		}
		if ((status == Status.REPRODUCED || status == Status.DISMISSED) && this.note.isEmpty()) {
			throw new IllegalArgumentException(AiText.str("assessment.note_required"));
		}
		updatedAt = System.currentTimeMillis();
	}

	public Status getStatus() {
		return status;
	}

	public Severity getSeverity() {
		return severity;
	}

	public String getNote() {
		return note;
	}

	public long getUpdatedAt() {
		return updatedAt;
	}

	public static AssessmentReview unreviewed() {
		return new AssessmentReview();
	}
}
