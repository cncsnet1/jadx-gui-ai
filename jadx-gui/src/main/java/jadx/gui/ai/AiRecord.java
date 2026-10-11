package jadx.gui.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiRecord {
	private String title;
	private String answer;
	private List<AiEvidence> evidence;
	private long createdAt;
	private String profileName;
	private String model;
	private String destination;
	private List<AiFinding> findings;

	private AiRecord() {
		// Required by project JSON deserialization with JDK Unsafe disabled.
	}

	public AiRecord(String title, String answer, List<AiEvidence> evidence) {
		this.title = title;
		this.answer = answer;
		this.evidence = new ArrayList<>(evidence);
		this.createdAt = System.currentTimeMillis();
		this.findings = new ArrayList<>(AiFindings.parse(answer));
	}

	public String getAnswer() {
		return answer;
	}

	public String getNarrative() {
		return AiFindings.narrative(answer);
	}

	public List<AiFinding> getFindings() {
		if (findings == null) {
			findings = new ArrayList<>(AiFindings.parse(answer));
		}
		return Collections.unmodifiableList(findings);
	}

	public AiRecord(String title, String answer, List<AiEvidence> evidence, AiProfile profile) {
		this(title, answer, evidence);
		profileName = profile.getName();
		model = profile.getModel();
		destination = profile.destination();
	}

	public String getProviderSummary() {
		return model == null ? "" : profileName + " · " + model + " · " + destination;
	}

	public List<AiEvidence> getEvidence() {
		return Collections.unmodifiableList(evidence);
	}

	public long getCreatedAt() {
		return createdAt;
	}

	@Override
	public String toString() {
		return title;
	}
}
