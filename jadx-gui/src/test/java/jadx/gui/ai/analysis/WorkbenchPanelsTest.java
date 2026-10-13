package jadx.gui.ai.analysis;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import jadx.gui.ai.AiEvidence;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchPanelsTest {
	@Test
	void cryptoIndexKeepsConstantsSeparateFromDataFlowAndDoesNotPromoteUnrelatedStrings() {
		String code = "Ljavax/crypto/Cipher;.getInstance\nLjavax/crypto/Cipher;.doFinal\n"
				+ "Ljavax/crypto/spec/SecretKeySpec;.<init>\nconst @12 = \"AES/CBC/PKCS5Padding\"\n"
				+ "const @20 = \"not-an-algorithm-AES\"\nconst @30 = \"secret-value\"\n"
				+ "A comment mentioning javax.crypto.Cipher.init\n";
		CryptoSummary summary = CryptoSummary.from(List.of(evidence("Cipher.class", code)));
		assertThat(summary.getReferences()).containsExactly("Ljavax/crypto/Cipher;.getInstance", "Ljavax/crypto/Cipher;.doFinal",
				"Ljavax/crypto/spec/SecretKeySpec;.<init>");
		assertThat(summary.getAlgorithmCandidates()).containsExactly("AES/CBC/PKCS5Padding");
		assertThat(CryptoSummary.from(List.of(evidence("other", "AES is mentioned in prose"))).getAlgorithmCandidates()).isEmpty();
	}

	@Test
	void protectionAndTechnologyChooseUsefulNextStepsWithoutEquatingUnknownWithUnprotected() {
		ApkProfile profile = new ApkProfile();
		profile.fact(ApkProfile.Kind.PROTECTION, "protection_unknown", "", ApkProfile.Confidence.UNKNOWN, List.of());
		assertThat(ApplicationReportPanel.recommendedAction(profile)).isEqualTo("inspect_requests");
		profile.dex();
		profile.fact(ApkProfile.Kind.TECHNOLOGY, "technology_flutter", "Flutter", ApkProfile.Confidence.OBSERVED, List.of());
		profile.recommend("inspect_native");
		assertThat(ApplicationReportPanel.recommendedAction(profile)).isEqualTo("inspect_native");
		profile.recommend("inspect_javascript");
		assertThat(ApplicationReportPanel.recommendedAction(profile)).isEqualTo("inspect_javascript");
		profile.recommend("check_protection");
		assertThat(ApplicationReportPanel.recommendedAction(profile)).isEqualTo("check_protection");
	}

	@Test
	void sourceRemainsAvailableWhileAiIsBusyAndProjectResetDropsOldEvidence() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AtomicReference<AiEvidence> opened = new AtomicReference<>();
			AtomicInteger investigations = new AtomicInteger();
			KeyCodePanel panel = new KeyCodePanel((finding, goal) -> investigations.incrementAndGet(), opened::set, () -> {
			});
			AiEvidence evidence = evidence("sample.Crypto.encrypt", "Ljavax/crypto/Cipher;.doFinal");
			AnalysisReport report = new AnalysisReport();
			report.setFingerprint("first");
			report.add(new AnalysisFinding("F1", AnalysisFinding.Kind.CRYPTO, "sample.Crypto.encrypt", evidence));
			report.setComplete(true);
			panel.setReport(report);
			JButton trace = button(panel, WorkbenchText.str("key.trace.crypto"));
			assertThat(trace.isEnabled()).isTrue();
			trace.doClick();
			assertThat(investigations.get()).isEqualTo(1);
			panel.setBusy(true);
			assertThat(trace.isEnabled()).isFalse();
			JButton source = button(panel, WorkbenchText.str("key.source"));
			assertThat(source.isEnabled()).isTrue();
			source.doClick();
			assertThat(opened.get()).isSameAs(evidence);
			panel.setReport(null);
			assertThat(source.isEnabled()).isFalse();
			assertThat(components(panel, JTextArea.class)).allMatch(area -> !area.getText().contains("sample.Crypto.encrypt"));
		});
	}

	@Test
	void nativeAndJavaScriptInventoryAreExplainedOnlyOnExplicitRequestWithActualEvidence() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AtomicReference<AiEvidence> opened = new AtomicReference<>();
			List<AnalysisFinding> investigations = new ArrayList<>();
			KeyCodePanel panel = new KeyCodePanel((finding, goal) -> {
				assertThat(goal).isEqualTo(AnalysisGoal.BEHAVIOR);
				investigations.add(finding);
			}, opened::set, () -> {
			});
			AiEvidence script = evidence("assets/apps/demo/www/app-service.js", "Script entry observed");
			ApkProfile profile = new ApkProfile();
			profile.fact(ApkProfile.Kind.VISIBILITY, "visibility_javascript", "1", ApkProfile.Confidence.OBSERVED, List.of(script));
			ApkProfile.NativeLibrary library = new ApkProfile.NativeLibrary("lib/arm64-v8a/libdemo.so", "arm64-v8a");
			library.header(64, "AArch64");
			library.symbol("JNI_OnLoad", false);
			library.evidence(evidence(library.getPath(), "ELF header observed"));
			profile.addNative(library);
			AnalysisReport report = new AnalysisReport();
			report.setProfile(profile);
			report.setComplete(true);
			panel.setReport(report);
			JList<?> list = components(panel, JList.class).get(0);
			assertThat(list.getModel().getSize()).isZero();
			components(panel, javax.swing.JCheckBox.class).stream()
					.filter(box -> box.getText().equals(WorkbenchText.str("triage.show_background"))).findFirst().orElseThrow().doClick();
			assertThat(list.getModel().getSize()).isEqualTo(2);
			button(panel, WorkbenchText.str("key.source")).doClick();
			assertThat(opened.get()).isSameAs(script);
			assertThat(investigations).isEmpty();
			button(panel, WorkbenchText.str("key.trace.javascript")).doClick();
			assertThat(investigations).hasSize(1);
			assertThat(investigations.get(0).getKind()).isEqualTo(AnalysisFinding.Kind.APK_STRUCTURE);
			assertThat(investigations.get(0).getEvidence()).anyMatch(evidence -> evidence.getTitle().equals(script.getTitle())
					&& evidence.getCode().contains("Script entry observed"));
			assertThat(KeyCodePanel.investigationDirection(investigations.get(0)))
					.isEqualTo(WorkbenchText.str("key.direction.javascript"));
			list.setSelectedIndex(1);
			assertThat(components(panel, JTextArea.class)).anyMatch(area -> area.getText().contains("JNI_OnLoad"));
			JButton explain = button(panel, WorkbenchText.str("key.trace.native"));
			panel.setBusy(true);
			assertThat(explain.isEnabled()).isFalse();
			explain.doClick();
			assertThat(investigations).hasSize(1);
			panel.setBusy(false);
			explain.doClick();
			assertThat(investigations).hasSize(2);
			assertThat(investigations.get(1).getKind()).isEqualTo(AnalysisFinding.Kind.APK_STRUCTURE);
			assertThat(investigations.get(1).getEvidence()).anyMatch(evidence -> evidence.getTitle().equals(library.getPath())
					&& evidence.getCode().contains("JNI_OnLoad"));
			assertThat(KeyCodePanel.investigationDirection(investigations.get(1)))
					.isEqualTo(WorkbenchText.str("key.direction.native"));
		});
	}

	@Test
	void overviewEvidenceAndNavigationWorkDuringBackgroundInvestigation() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AtomicInteger navigation = new AtomicInteger();
			AtomicReference<AiEvidence> opened = new AtomicReference<>();
			ApplicationReportPanel panel = new ApplicationReportPanel((finding, goal) -> {
			}, finding -> {
			}, () -> {
			},
					navigation::incrementAndGet, () -> {
					}, opened::set, () -> {
					});
			AiEvidence source = evidence("AndroidManifest.xml", "Android component observed");
			ApkProfile profile = new ApkProfile();
			profile.fact(ApkProfile.Kind.TECHNOLOGY, "technology_android", "DEX", ApkProfile.Confidence.OBSERVED, List.of(source));
			AnalysisReport report = new AnalysisReport();
			report.setProfile(profile);
			panel.setReport(report);
			panel.setBusy(true);
			button(panel, WorkbenchText.str("overview.requests")).doClick();
			assertThat(navigation.get()).isEqualTo(1);
			components(panel, JButton.class).stream().filter(button -> button.getText().equals(WorkbenchText.str("overview.sources")))
					.filter(Component::isEnabled).findFirst().orElseThrow().doClick();
			button(panel, WorkbenchText.str("overview.source")).doClick();
			assertThat(opened.get()).isSameAs(source);
		});
	}

	@Test
	void uniAppOverviewOpensTheActualRequestSourceAndOnlyStartsAiOnExplicitClick() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			FrameworkAnalysis analysis = frameworkFixture();
			AnalysisReport report = new AnalysisReport();
			report.setFrameworkAnalysis(analysis);
			report.setComplete(true);
			FrameworkAnalysis.Finding request = analysis.getFindings().stream()
					.filter(finding -> finding.getKind() == FrameworkAnalysis.Kind.REQUESTS).findFirst().orElseThrow();
			AtomicReference<AiEvidence> opened = new AtomicReference<>();
			List<AnalysisFinding> investigations = new ArrayList<>();
			ApplicationReportPanel panel = new ApplicationReportPanel((finding, goal) -> investigations.add(finding), finding -> {
			},
					() -> {
					}, () -> {
					}, () -> {
					}, opened::set, () -> {
					});
			panel.setReport(report);
			assertThat(investigations).isEmpty();
			assertThat(components(panel, JTextArea.class))
					.anyMatch(area -> area.getText().contains(WorkbenchText.str("framework.kind.requests"))
							&& area.getText().contains(WorkbenchText.str("framework.status.not_covered")));
			panel.setBusy(true);
			button(panel, WorkbenchText.str("framework.source")).doClick();
			assertThat(opened.get()).isSameAs(request.getEvidence().get(0));
			JButton trace = button(panel, WorkbenchText.str("framework.trace"));
			assertThat(trace.isEnabled()).isFalse();
			trace.doClick();
			assertThat(investigations).isEmpty();
			panel.setBusy(false);
			trace.doClick();
			assertThat(investigations).hasSize(1);
			assertThat(investigations.get(0).getKind()).isEqualTo(AnalysisFinding.Kind.FRAMEWORK_CODE);
			assertThat(KeyCodePanel.investigationDirection(report, investigations.get(0))).isEqualTo(request.getTask());
		});
	}

	@Test
	void frameworkFindingsArePrioritizedGroupedAndKeepTheirOriginalSourceValues() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			FrameworkAnalysis analysis = frameworkFixture();
			AnalysisReport report = new AnalysisReport();
			report.setFingerprint("uni-app");
			report.setFrameworkAnalysis(analysis);
			report.setComplete(true);
			List<AnalysisFinding> investigations = new ArrayList<>();
			AtomicReference<AiEvidence> opened = new AtomicReference<>();
			KeyCodePanel panel = new KeyCodePanel((finding, goal) -> investigations.add(finding), opened::set, () -> {
			});
			panel.setReport(report);
			panel.selectCategory("framework");
			JList<?> list = components(panel, JList.class).get(0);
			assertThat(list.getModel().getSize()).isEqualTo(3);
			button(panel, WorkbenchText.str("key.source")).doClick();
			assertThat(opened.get().getCode()).contains("uni.request", "ORIGINAL_TOKEN_123456789");
			assertThat(investigations).isEmpty();
			panel.selectCategory("identity");
			assertThat(list.getModel().getSize()).isEqualTo(1);
			assertThat(components(panel, JTextArea.class)).anyMatch(area -> area.getText().contains("ORIGINAL_PASSWORD_123456789"));
			button(panel, WorkbenchText.str("framework.trace")).doClick();
			assertThat(investigations).hasSize(1);
			assertThat(KeyCodePanel.investigationDirection(report, investigations.get(0)))
					.isEqualTo("Follow this stored session into its request consumers.");
			panel.setReport(null);
			assertThat(components(panel, JTextArea.class)).allMatch(area -> !area.getText().contains("ORIGINAL_PASSWORD_123456789"));
		});
	}

	private static FrameworkAnalysis frameworkFixture() {
		FrameworkAnalysis analysis = new FrameworkAnalysis();
		analysis.framework("uni-app");
		analysis.read("fixture.apk!/assets/apps/demo/www/app-service.js");
		analysis.covered(FrameworkAnalysis.Kind.PAGES);
		analysis.covered(FrameworkAnalysis.Kind.REQUESTS);
		analysis.covered(FrameworkAnalysis.Kind.AUTH_STORAGE);
		analysis.add(new FrameworkAnalysis.Finding(FrameworkAnalysis.Kind.PAGES, "pages/login", "A page was declared.",
				"Follow the declared page into its business script.", evidence("pages.json:2", "{\"path\":\"pages/login\"}")));
		analysis.add(new FrameworkAnalysis.Finding(FrameworkAnalysis.Kind.REQUESTS, "request wrapper", "A wrapper calls uni.request.",
				"Follow this wrapper's callers, headers and request parameters.", evidence("app-service.js:7",
						"uni.request({url:'/account',header:{Authorization:'ORIGINAL_TOKEN_123456789'}})")));
		analysis.add(new FrameworkAnalysis.Finding(FrameworkAnalysis.Kind.AUTH_STORAGE, "session storage", "A session is stored.",
				"Follow this stored session into its request consumers.", evidence("app-service.js:19",
						"uni.setStorageSync('password','ORIGINAL_PASSWORD_123456789')")));
		analysis.complete(true);
		return analysis;
	}

	@Test
	void bothLocalesHaveTheSameWorkbenchMessages() {
		ResourceBundle.Control control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
		ResourceBundle english = ResourceBundle.getBundle("workbench.messages", Locale.ENGLISH, control);
		ResourceBundle chinese = ResourceBundle.getBundle("workbench.messages", Locale.SIMPLIFIED_CHINESE, control);
		assertThat(chinese.keySet()).containsExactlyInAnyOrderElementsOf(english.keySet());
		assertThat(chinese.getString("overview.heading")).isNotEqualTo(english.getString("overview.heading"));
	}

	private static AiEvidence evidence(String title, String code) {
		return new AiEvidence(null, title, code, AiEvidence.fingerprint(code), false);
	}

	private static JButton button(Container parent, String text) {
		return components(parent, JButton.class).stream().filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
	}

	private static <T extends Component> List<T> components(Container parent, Class<T> type) {
		List<T> result = new ArrayList<>();
		for (Component child : parent.getComponents()) {
			if (type.isInstance(child)) {
				result.add(type.cast(child));
			}
			if (child instanceof Container) {
				result.addAll(components((Container) child, type));
			}
		}
		return result;
	}
}
