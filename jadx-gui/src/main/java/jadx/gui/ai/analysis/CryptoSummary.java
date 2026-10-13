package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.gui.ai.AiEvidence;

/** An index of recorded references and exact constants, deliberately not a data-flow verdict. */
final class CryptoSummary {
	private static final Pattern CALL = Pattern.compile(
			"(?m)^L?(?:javax[/\\.]crypto[/\\.](?:spec[/\\.])?|java[/\\.]security[/\\.])"
					+ "(?:Cipher|Mac|SecretKeyFactory|SecretKeySpec|IvParameterSpec|GCMParameterSpec|PBEKeySpec|Signature|MessageDigest)"
					+ ";?\\.[A-Za-z<>][A-Za-z0-9_<>]*$");
	private static final Pattern CONSTANT = Pattern.compile("(?m)^const @\\d+ = \"([^\"\\r\\n]{1,100})\"$");
	private static final Pattern ALGORITHM = Pattern.compile(
			"(?i)(?:AES|DES|DESede|RSA|Blowfish|ChaCha20(?:-Poly1305)?)(?:/(?:ECB|CBC|CFB(?:8|64|128)?|OFB(?:8|64|128)?|CTR|GCM|CCM|PCBC|NONE)"
					+ "/(?:NoPadding|PKCS5Padding|PKCS7Padding|PKCS1Padding|ISO10126Padding|OAEPPadding|OAEPWithSHA-(?:1|224|256|384|512)AndMGF1Padding))?"
					+ "|Hmac(?:SHA(?:1|224|256|384|512)|MD5)|(?:SHA(?:1|224|256|384|512)|MD5)with(?:RSA|ECDSA|DSA)"
					+ "|PBKDF2WithHmacSHA(?:1|224|256|384|512)|SHA-(?:1|224|256|384|512)|MD5");
	private static final Pattern STANDARD_TYPE = Pattern.compile(
			"L?(?:javax/crypto/(?:spec/)?(?:Cipher|Mac|SecretKeyFactory|SecretKeySpec|IvParameterSpec|GCMParameterSpec|PBEKeySpec)"
					+ "|java/security/(?:Signature|MessageDigest))");
	private final List<String> references;
	private final List<String> algorithmCandidates;

	private CryptoSummary(List<String> references, List<String> algorithmCandidates) {
		this.references = references;
		this.algorithmCandidates = algorithmCandidates;
	}

	static boolean isKnownAlgorithm(String value) {
		return ALGORITHM.matcher(value).matches();
	}

	static boolean isKnownTypeToken(String value) {
		return STANDARD_TYPE.matcher(value).matches();
	}

	static CryptoSummary from(List<AiEvidence> evidence) {
		Set<String> references = new LinkedHashSet<>();
		Set<String> algorithms = new LinkedHashSet<>();
		for (AiEvidence source : evidence) {
			String code = source.getCode();
			if (code == null) {
				continue;
			}
			Matcher call = CALL.matcher(code);
			while (call.find()) {
				references.add(call.group());
			}
			Matcher constant = CONSTANT.matcher(code);
			while (constant.find()) {
				if (isKnownAlgorithm(constant.group(1))) {
					algorithms.add(constant.group(1));
				}
			}
		}
		return new CryptoSummary(new ArrayList<>(references), new ArrayList<>(algorithms));
	}

	List<String> getReferences() {
		return references;
	}

	List<String> getAlgorithmCandidates() {
		return algorithmCandidates;
	}
}
