package jadx.gui.ai.analysis;

import org.junit.jupiter.api.Test;

import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiEvidence;

import static org.assertj.core.api.Assertions.assertThat;

class CommunicationEvidenceTest {
	@Test
	void linksOnlyCryptoInExactSourceOrTracedMethods() {
		JadxNodeRef request = method("request");
		JadxNodeRef caller = method("login");
		ApiEndpoint endpoint = new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "POST", "/login", "Api.request", request);
		AnalysisReport report = new AnalysisReport();
		AnalysisRules rules = new AnalysisRules(report);
		rules.behavior("Ljavax/crypto/Cipher;.doFinal", "", "Api.login", caller);
		rules.behavior("Ljavax/crypto/Cipher;.doFinal", "", "Api.unrelated", method("unrelated"));
		assertThat(CommunicationEvidence.crypto(report, endpoint, null)).isEmpty();
		ApiDossier dossier = new ApiDossier("A1");
		CallPath path = new CallPath(4);
		path.add(request, 0);
		path.add(caller, 1);
		dossier.setCallPath(path);
		assertThat(CommunicationEvidence.crypto(report, endpoint, dossier)).extracting(AiEvidence::getTitle).containsExactly("Api.login");
	}

	@Test
	void nullResourceReferencesDoNotMatchUnrelatedCrypto() {
		AnalysisReport report = new AnalysisReport();
		new AnalysisRules(report).behavior("Ljavax/crypto/Cipher;.doFinal", "", "unknown source", null);
		ApiEndpoint endpoint = new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "GET", "/page", "assets/page.js", null);
		assertThat(CommunicationEvidence.crypto(report, endpoint, null)).isEmpty();
	}

	private static JadxNodeRef method(String name) {
		return new JadxNodeRef(RefType.METHOD, "example.Api", name + "()V");
	}
}
