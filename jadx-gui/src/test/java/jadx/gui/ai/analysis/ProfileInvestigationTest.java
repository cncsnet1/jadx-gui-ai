package jadx.gui.ai.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.fixtures.AnalysisFixture;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileInvestigationTest {
	@Test
	void profileOnlyReportSeedsCitableProtectionFrameworkAndElfWithoutJavaNodes() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = profileOnlyReport();
			assertThat(report.getFindings()).isEmpty();
			assertThat(report.getApis()).isEmpty();
			assertThat(report.hasEvidence()).isTrue();
			List<String> requests = new ArrayList<>();
			AtomicReference<InvestigationRun> published = new AtomicReference<>();
			AiClient client = (model, messages, temperature, tokens, token, output) -> {
				requests.add(messages.toString());
				assertThat(published.get().getEvidence().entries()).hasSize(3);
				assertThat(published.get().getNodes()).isEmpty();
				return requests.size() == 1
						? "{\"calls\":[],\"questions\":[\"Native behavior requires additional evidence\"]}"
						: "Protection indicator [S1], Flutter framework [S2], and an ELF symbol index [S3].";
			};
			InvestigationRun run = new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.EXPLORE,
					"Explain what is visible", null, null, new AiProfile(), new AiCancellation(), published::set, ignored -> {
					});
			assertThat(run.getSelected()).containsExactly("K1", "K2", "K1000");
			assertThat(run.getNodes()).isEmpty();
			assertThat(run.getEvidence().entries()).allSatisfy(entry -> {
				assertThat(entry.isBehavioralCode()).isFalse();
				assertThat(entry.getSource().getNode()).isNull();
			});
			assertThat(run.getAnswer().getEvidence()).hasSize(3);
			assertThat(run.getAnswer().getOpportunities()).isNull();
			assertThat(requests.get(0)).contains("360 Jiagu", "Flutter", "JNI_OnLoad", "No unpacking", "not Java nodes");
			assertThat(requests.get(1)).contains("Do not fabricate function behavior", "No unpacking");
			report.putInvestigation(run);
			var gson = defaultGsonBuilder().create();
			AnalysisReport restored = gson.fromJson(gson.toJson(report), AnalysisReport.class);
			assertThat(restored.hasEvidence()).isTrue();
			assertThat(ProfileLead.candidates(restored)).extracting(AnalysisFinding::getId)
					.containsExactlyElementsOf(ProfileLead.candidates(report).stream().map(AnalysisFinding::getId)
							.collect(java.util.stream.Collectors.toList()));
			assertThat(InvestigationService.find(restored, "K1000").getEvidence().get(0).getCode()).contains("JNI_OnLoad");
		}
	}

	@Test
	void firstModelFailureOrCancellationPreservesProfileSeed() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			for (boolean cancel : List.of(false, true)) {
				AtomicReference<InvestigationRun> published = new AtomicReference<>();
				AiClient client = (model, messages, temperature, tokens, token, output) -> {
					assertThat(published.get().getEvidence().entries()).hasSize(3);
					if (cancel) {
						token.cancel();
						token.check();
					}
					throw new IOException("offline");
				};
				assertThatThrownBy(() -> new InvestigationService(decompiler, client).investigate(profileOnlyReport(),
						AnalysisGoal.BEHAVIOR, "Explain structure", null, null, new AiProfile(), new AiCancellation(), published::set,
						ignored -> {
						})).isInstanceOf(cancel ? InterruptedException.class : IOException.class);
				assertThat(published.get().getState()).isEqualTo(cancel ? InvestigationRun.State.CANCELLED : InvestigationRun.State.FAILED);
				assertThat(published.get().getEvidence().entries()).hasSize(3);
				assertThat(published.get().getNodes()).isEmpty();
				assertThat(published.get().getCalls()).isZero();
			}
		}
	}

	@Test
	void inventedJavaTargetIsRejectedAndFocusedLibraryUsesOnlyAuthoritativeMetadata() throws Exception {
		try (JadxDecompiler decompiler = open()) {
			AnalysisReport report = profileOnlyReport();
			AnalysisFinding focus = ProfileLead.nativeFinding(report, report.getProfile().getNativeLibraries().get(0));
			AtomicInteger requests = new AtomicInteger();
			AiClient client = (model, messages, temperature, tokens, token, output) -> {
				switch (requests.incrementAndGet()) {
					case 1:
						return "{\"calls\":[{\"operation\":\"inspect_symbol\",\"target\":\"N1\"}]}";
					case 2:
						assertThat(messages.toString()).contains("Use a node identifier from the current investigation catalog");
						return "{\"calls\":[],\"questions\":[\"No Java node is available\"]}";
					default:
						return "Only an ELF header and symbol index were inspected [S1].";
				}
			};
			InvestigationRun run = new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.BEHAVIOR,
					"Explain this library", focus, null, new AiProfile(), new AiCancellation(), ignored -> {
					}, ignored -> {
					});
			assertThat(run.getNodes()).isEmpty();
			assertThat(run.getSelected()).containsExactly("K1000");
			assertThat(run.getEvidence().entries()).hasSize(1);
			assertThat(run.getSteps().stream().filter(step -> step.getOperation().equals("inspect_symbol")))
					.singleElement().satisfies(step -> assertThat(step.getEvidence()).isEmpty());
			assertThat(ProfileLead.nativeFinding(report, new ApkProfile.NativeLibrary("not-in-report.so", "arm64"))).isNull();
		}
	}

	@Test
	void unknownLabelsAloneDoNotEnableAnalysisAndScriptFocusUsesItsStoredSource() {
		AnalysisReport empty = new AnalysisReport();
		ApkProfile profile = new ApkProfile();
		profile.fact(ApkProfile.Kind.PROTECTION, "protection_unknown", "", ApkProfile.Confidence.UNKNOWN, List.of());
		empty.setProfile(profile);
		assertThat(empty.hasEvidence()).isFalse();
		assertThat(ProfileLead.candidates(empty)).isEmpty();
		AnalysisReport report = profileOnlyReport();
		AiEvidence resource = report.getProfile().getFacts().get(2).getEvidence().get(0);
		AnalysisFinding script =
				ProfileLead.scriptFinding(report, new AiEvidence(null, resource.getTitle(), "untrusted replacement", "", false));
		assertThat(script.getId()).isEqualTo("K2000");
		assertThat(script.getEvidence().get(0).getCode()).contains("Presence only", "JavaScript asset")
				.doesNotContain("untrusted replacement");
		assertThat(ProfileLead.directionKind(script)).isEqualTo("javascript");
		assertThat(AnalysisInvestigator.select("{\"inspect\":[\"K2000\",\"K9999\",\"N1\"]}", ProfileLead.candidates(report)))
				.extracting(AnalysisFinding::getId).containsExactly("K2000");
	}

	private static AnalysisReport profileOnlyReport() {
		ApkProfile profile = new ApkProfile();
		profile.fact(ApkProfile.Kind.PROTECTION, "protection_marker", "360 Jiagu", ApkProfile.Confidence.SUSPECTED,
				List.of(source("app.apk!/lib/arm64-v8a/libjiagu.so", "Known protection-associated filename")));
		profile.fact(ApkProfile.Kind.TECHNOLOGY, "technology_flutter", "Flutter", ApkProfile.Confidence.OBSERVED,
				List.of(source("app.apk!/lib/arm64-v8a/libflutter.so", "Flutter runtime library")));
		profile.fact(ApkProfile.Kind.VISIBILITY, "visibility_javascript", "1", ApkProfile.Confidence.OBSERVED,
				List.of(source("app.apk!/assets/app.js", "JavaScript asset")));
		ApkProfile.NativeLibrary library = new ApkProfile.NativeLibrary("app.apk!/lib/arm64-v8a/libbusiness.so", "arm64-v8a");
		library.header(64, "AArch64");
		library.symbol("JNI_OnLoad", false);
		library.symbol("memcpy", true);
		library.limit("elf_symbols_unavailable");
		library.evidence(source(library.getPath(), "ELF header and partial symbol table"));
		profile.addNative(library);
		profile.complete();
		AnalysisReport report = new AnalysisReport();
		report.setFingerprint("profile-only-fixture");
		report.setProfile(profile);
		report.setComplete(true);
		return report;
	}

	private static AiEvidence source(String title, String code) {
		return new AiEvidence(null, title, code, AiEvidence.fingerprint(code), false);
	}

	private static JadxDecompiler open() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile()));
		args.setUseDxInput(false);
		JadxDecompiler decompiler = new JadxDecompiler(args);
		decompiler.load();
		return decompiler;
	}
}
