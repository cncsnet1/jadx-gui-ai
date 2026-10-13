package jadx.gui.ai.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.AiRecord;
import jadx.gui.ai.analysis.AnalysisFinding.Kind;
import jadx.gui.ai.fixtures.AnalysisFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisScannerTest {
	@TempDir
	Path temporary;

	private JadxDecompiler decompiler() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile()));
		args.setInlineMethods(false);
		args.setUseDxInput(false);
		JadxDecompiler decompiler = new JadxDecompiler(args);
		decompiler.load();
		return decompiler;
	}

	@Test
	void bytecodeScanFindsRealConstantsAndAiAutomaticallyReadsCallers() throws Exception {
		try (JadxDecompiler decompiler = decompiler()) {
			List<AnalysisReport> updates = new ArrayList<>();
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), updates::add);
			assertThat(report.isComplete()).isTrue();
			assertThat(updates).isNotEmpty();
			assertThat(report.getFindings()).extracting(AnalysisFinding::getKind).contains(Kind.CREDENTIAL, Kind.ENDPOINT, Kind.LOGIN);
			assertThat(report.getGaps()).isEmpty();
			AnalysisFinding login = report.getFindings().stream().filter(f -> f.getKind() == Kind.LOGIN
					&& f.getEvidence().get(0).getTitle().contains("submitLogin")).findFirst().orElseThrow();
			List<String> requests = new ArrayList<>();
			AtomicInteger calls = new AtomicInteger();
			AiClient client = (model, messages, temperature, tokens, cancellation, output) -> {
				requests.add(messages.toString());
				return calls.getAndIncrement() == 0 ? "{\"inspect\":[\"" + login.getId() + "\"]}" : "Authentication observation [S1]";
			};
			AiRecord answer = new AnalysisInvestigator(decompiler, client).investigate(report, AnalysisGoal.LOGIN,
					"Explain login", null, new AiProfile(), new AiCancellation(), ignored -> {
					});
			assertThat(calls.get()).isEqualTo(2);
			assertThat(answer.getEvidence()).anyMatch(e -> e.getTitle().contains("caller depth") && e.getCode().contains("submitLogin"));
			assertThat(requests.toString()).contains("private-query-token", "fixture-local-secret");
			assertThat(answer.getEvidence()).anyMatch(e -> e.getCode().contains("fixture-local-secret")
					&& e.getCode().contains("private-query-token"));
			assertThat(answer.getEvidence().size()).isLessThanOrEqualTo(AnalysisInvestigator.MAX_SOURCES);
			assertThat(answer.getEvidence().stream().mapToInt(e -> e.getCode().length()).sum())
					.isLessThanOrEqualTo(AnalysisInvestigator.MAX_CONTEXT);
			assertThat(report.markdown()).contains("SAMPLE_PRIVATE_PASSWORD_42", "private-query-token", "fixture-local-secret");
		}
	}

	@Test
	void opportunityInvestigationUsesActualCodeOwnershipIncludingSharedDefinitions() throws Exception {
		try (JadxDecompiler decompiler = decompiler()) {
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), ignored -> {
			});
			AnalysisFinding login = report.getFindings().stream().filter(f -> f.getKind() == Kind.LOGIN
					&& f.getEvidence().get(0).getTitle().contains("submitLogin")).findFirst().orElseThrow();
			AnalysisFinding shared = new AnalysisFinding("F999", Kind.CREDENTIAL, "fixture-local-secret", login.getEvidence().get(0));
			AnalysisInvestigator collector = new AnalysisInvestigator(decompiler, null);
			var sources = collector.collectContext(List.of(login, shared), false, new AiCancellation(), ignored -> {
			});
			assertThat(sources.codeSources).isNotEmpty();
			assertThat(sources.ownership.get(login.getId())).containsAll(sources.codeSources);
			assertThat(sources.ownership.get(shared.getId())).containsAll(sources.codeSources);
			assertThat(sources.evidence).filteredOn(e -> e.getTitle().contains("[definition]")).hasSize(1);
			assertThat(sources.evidence).anyMatch(e -> e.getTitle().contains("caller depth"));
			var focused = collector.collectContext(List.of(login), true, new AiCancellation(), ignored -> {
			});
			String sourceId = focused.codeSources.iterator().next();
			String quote = "submitLogin()";
			assertThat(focused.evidence.get(Integer.parseInt(sourceId.substring(1)) - 1).getCode()).contains(quote);
			AiClient model = (name, messages, temperature, tokens, token, output) -> {
				String sent = messages.toString();
				assertThat(sent).contains("Local source ownership", sourceId, login.getId(), "jadx-opportunities",
						"fixture-local-secret", "private-query-token")
						.doesNotContain("jadx-findings", "SAMPLE_PRIVATE_PASSWORD_42");
				return OpportunityAnalysisTest.response(OpportunityAnalysisTest.card(login.getId(), sourceId, quote));
			};
			AiRecord answer = new AnalysisInvestigator(decompiler, model).investigate(report, AnalysisGoal.EXPLORE, "", login,
					new AiProfile(), new AiCancellation(), ignored -> {
					});
			assertThat(answer.getOpportunities().getCandidates()).hasSize(1);
			assertThat(answer.getOpportunities().getSelectedIds()).containsExactly(login.getId());
			assertThat(answer.getOpportunities().getCatalogSize()).isEqualTo(1);
			assertThat(answer.getOpportunities().getCandidates().get(0).getPriority()).isEqualTo(OpportunityAnalysis.Priority.FIRST);
		}
	}

	@Test
	void cancellationDoesNotStartModelCalls() throws Exception {
		try (JadxDecompiler decompiler = decompiler()) {
			AiCancellation token = new AiCancellation();
			token.cancel();
			assertThatThrownBy(() -> new ApkAnalysisScanner(decompiler).scan("fixture", token, ignored -> {
			}))
					.isInstanceOf(InterruptedException.class);
			AiClient client = (model, messages, temperature, tokens, cancellation, output) -> {
				throw new AssertionError("must not call model");
			};
			assertThatThrownBy(() -> new AnalysisInvestigator(decompiler, client).investigate(new AnalysisReport(), AnalysisGoal.OVERVIEW,
					"", null, new AiProfile(), token, ignored -> {
					})).isInstanceOf(InterruptedException.class);
		}
	}

	@Test
	void fingerprintChecksContentEvenWhenFileSizeIsUnchanged() throws Exception {
		Path file = temporary.resolve("fixture.apk");
		Files.writeString(file, "first");
		String first = ApkAnalysisScanner.fingerprint(List.of(file), new AiCancellation());
		Files.writeString(file, "other");
		assertThat(ApkAnalysisScanner.fingerprint(List.of(file), new AiCancellation())).isNotEqualTo(first);
		assertThat(ApkAnalysisScanner.fingerprint(List.of(temporary), new AiCancellation())).isEmpty();
	}
}
