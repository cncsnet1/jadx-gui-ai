package jadx.gui.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import jadx.api.data.impl.JadxNodeRef;

/** Serializable evidence; never retains live GUI/decompiler objects or API credentials. */
public final class AiEvidence {
	private JadxNodeRef node;
	private String title;
	private String code;
	private String fingerprint;
	private boolean truncated;

	private AiEvidence() {
		// Required by project JSON deserialization with JDK Unsafe disabled.
	}

	public AiEvidence(JadxNodeRef node, String title, String code, String fingerprint, boolean truncated) {
		this.node = node;
		this.title = title;
		this.code = code;
		this.fingerprint = fingerprint;
		this.truncated = truncated;
	}

	public JadxNodeRef getNode() {
		return node;
	}

	public String getTitle() {
		return title;
	}

	public String getCode() {
		return code;
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public boolean isTruncated() {
		return truncated;
	}

	public static String fingerprint(String code) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (byte b : hash) {
				out.append(String.format("%02x", b & 0xff));
			}
			return out.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public String toString() {
		return title;
	}
}
