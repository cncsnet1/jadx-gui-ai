package jadx.gui.ai.analysis;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import jadx.gui.ai.AiEvidence;

import static org.assertj.core.api.Assertions.assertThat;

class MultiRoutePanelsTest {
	@Test
	void overviewSelectionNavigatesToTheSelectedRouteAndRetainsItsActualSource() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AnalysisReport report = fixture();
			AtomicReference<AiEvidence> opened = new AtomicReference<>();
			List<AnalysisFinding> investigated = new ArrayList<>();
			KeyCodePanel code = new KeyCodePanel((finding, goal) -> investigated.add(finding), opened::set, () -> {
			});
			code.setReport(report);
			ApplicationReportPanel overview = overview(opened, investigated);
			List<String> navigated = new ArrayList<>();
			overview.setRouteNavigation(id -> {
				navigated.add(id);
				code.selectRoute(id);
			});
			overview.setReport(report);
			assertThat(routeSelector(overview).getItemCount()).isEqualTo(3);
			assertThat(routeSelector(overview).getSelectedItem().toString()).startsWith("uni-app");
			button(overview, "framework.all").doClick();
			assertThat(navigated).containsExactly("uni-app");
			assertThat(catalog(code).getModel().getSize()).isEqualTo(1);
			button(code, "key.source").doClick();
			assertThat(opened.get().getCode()).contains("UNI_ORIGINAL_TOKEN").doesNotContain("RN_ORIGINAL_TOKEN");
			routeSelector(overview).setSelectedIndex(1);
			button(overview, "framework.all").doClick();
			assertThat(navigated).containsExactly("uni-app", "react-native");
			assertThat(routeSelector(code).getSelectedItem()).isEqualTo("React Native");
			assertThat(catalog(code).getModel().getSize()).isEqualTo(1);
			button(code, "key.source").doClick();
			assertThat(opened.get().getCode()).contains("RN_ORIGINAL_TOKEN").doesNotContain("UNI_ORIGINAL_TOKEN");
			assertThat(investigated).isEmpty();
			overview.setBusy(true);
			assertThat(button(overview, "framework.trace").isEnabled()).isFalse();
			button(overview, "framework.source").doClick();
			assertThat(opened.get().getCode()).contains("RN_ORIGINAL_TOKEN");
			overview.setBusy(false);
			button(overview, "framework.trace").doClick();
			assertThat(investigated).hasSize(1);
			assertThat(investigated.get(0).getEvidence()).anyMatch(source -> source.getCode().contains("RN_ORIGINAL_TOKEN"));
			assertThat(ProfileLead.frameworkTask(report, investigated.get(0))).isEqualTo("Follow the React Native request.");
			overview.setReport(report.snapshot());
			assertThat(routeSelector(overview).getSelectedItem().toString()).startsWith("React Native");
		});
	}

	@Test
	void inventoryOnlyRouteExplainsTheDecoderGapWithoutOfferingAnInventedAiFinding() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AnalysisReport report = fixture();
			AtomicReference<AiEvidence> opened = new AtomicReference<>();
			List<AnalysisFinding> investigated = new ArrayList<>();
			ApplicationReportPanel overview = overview(opened, investigated);
			overview.setReport(report);
			routeSelector(overview).setSelectedIndex(2);
			assertThat(routeSelector(overview).getSelectedItem().toString())
					.contains("Flutter", WorkbenchText.str("routes.status.inventory_only"));
			assertThat(components(overview, JButton.class))
					.noneMatch(button -> button.getText().equals(WorkbenchText.str("framework.trace")));
			button(overview, "framework.coverage").doClick();
			assertThat(components(overview, JTextArea.class))
					.anyMatch(area -> area.getText().contains(KeyCodePanel.frameworkLimitation("dart_aot_not_decompiled")));
			KeyCodePanel code = new KeyCodePanel((finding, goal) -> investigated.add(finding), opened::set, () -> {
			});
			code.setReport(report);
			code.selectRoute("flutter");
			assertThat(catalog(code).getModel().getSize()).isZero();
			assertThat(components(code, JTextArea.class)).anyMatch(area -> area.getText().contains("lib/arm64-v8a/libapp.so")
					&& area.getText().contains(KeyCodePanel.frameworkLimitation("dart_aot_not_decompiled")));
			assertThat(components(code, JLabel.class))
					.anyMatch(label -> label.getText().contains(WorkbenchText.str("routes.status.inventory_only")));
			JButton ai = components(code, JButton.class).stream()
					.filter(button -> WorkbenchText.str("key.ai_source").equals(button.getToolTipText())).findFirst().orElseThrow();
			assertThat(ai.isVisible()).isFalse();
			assertThat(ai.isEnabled()).isFalse();
			ai.doClick();
			button(code, "key.source").doClick();
			assertThat(opened.get().getCode()).contains("Dart AOT functions remain undecoded");
			assertThat(investigated).isEmpty();
		});
	}

	@Test
	void explicitBackgroundToggleRestoresOnlyCandidatesFromTheSelectedRoute() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AtomicReference<AiEvidence> opened = new AtomicReference<>();
			KeyCodePanel code = new KeyCodePanel((finding, goal) -> {
			}, opened::set, () -> {
			});
			code.setReport(fixture());
			code.selectRoute("uni-app");
			JList<?> list = catalog(code);
			JCheckBox background = components(code, JCheckBox.class).stream()
					.filter(box -> box.getText().equals(WorkbenchText.str("triage.show_background"))).findFirst().orElseThrow();
			assertThat(background.isSelected()).isFalse();
			assertThat(list.getModel().getSize()).isEqualTo(1);
			assertThat(components(code, JLabel.class))
					.anyMatch(label -> label.getText().contains(String.format(WorkbenchText.str("triage.folded"), 1)));
			background.doClick();
			assertThat(list.getModel().getSize()).isEqualTo(2);
			List<String> originals = new ArrayList<>();
			for (int i = 0; i < list.getModel().getSize(); i++) {
				list.setSelectedIndex(i);
				button(code, "key.source").doClick();
				originals.add(opened.get().getCode());
			}
			assertThat(String.join("\n", originals)).contains("UNI_ORIGINAL_TOKEN", "SDK_ORIGINAL_TOKEN")
					.doesNotContain("RN_ORIGINAL_TOKEN");
			background.doClick();
			assertThat(list.getModel().getSize()).isEqualTo(1);
			button(code, "key.source").doClick();
			assertThat(opened.get().getCode()).contains("UNI_ORIGINAL_TOKEN").doesNotContain("SDK_ORIGINAL_TOKEN");
			code.setReport(null);
			assertThat(list.getModel().getSize()).isZero();
			assertThat(button(code, "key.source").isEnabled()).isFalse();
		});
	}

	private static AnalysisReport fixture() {
		FrameworkAnalysis analysis = new FrameworkAnalysis();
		AiEvidence uni = evidence("sample.apk!/assets/apps/demo/www/app-service.js:4",
				"uni.request({url:'/account',header:{Authorization:'UNI_ORIGINAL_TOKEN'}})");
		analysis.route("uni-app", "uni-app", List.of(uni), "Follow the uni-app request.");
		analysis.activate("uni-app");
		analysis.routeStatus(FrameworkAnalysis.RouteStatus.COMPLETE);
		analysis.routeRead(uni.getTitle());
		analysis.covered(FrameworkAnalysis.Kind.REQUESTS);
		analysis.add(new FrameworkAnalysis.Finding(FrameworkAnalysis.Kind.REQUESTS, "uni-app account request", "Static request call",
				"Follow the uni-app request.", uni));
		analysis.add(new FrameworkAnalysis.Finding(FrameworkAnalysis.Kind.REQUESTS, "SDK request", "Runtime code observation",
				"Inspect this runtime source before attributing it to the application.",
				evidence("sample.apk!/assets/node_modules/vendor/sdk.js:2", "fetch('/telemetry',{headers:{token:'SDK_ORIGINAL_TOKEN'}})")));
		AiEvidence react = evidence("sample.apk!/assets/index.android.bundle:12",
				"fetch('/profile',{headers:{Authorization:'RN_ORIGINAL_TOKEN'}})");
		analysis.route("react-native", "React Native", List.of(react), "Follow the React Native request.");
		analysis.activate("react-native");
		analysis.routeStatus(FrameworkAnalysis.RouteStatus.PARTIAL);
		analysis.routeRead(react.getTitle());
		analysis.covered(FrameworkAnalysis.Kind.REQUESTS);
		analysis.add(new FrameworkAnalysis.Finding(FrameworkAnalysis.Kind.REQUESTS, "React Native profile request", "Static request call",
				"Follow the React Native request.", react));
		AiEvidence nativeSource = evidence("sample.apk!/lib/arm64-v8a/libapp.so", "ELF inventory; Dart AOT functions remain undecoded");
		analysis.route("flutter", "Flutter", List.of(nativeSource), "Inspect assets and native inventory.");
		analysis.activate("flutter");
		analysis.routeStatus(FrameworkAnalysis.RouteStatus.INVENTORY_ONLY);
		analysis.gap("dart_aot_not_decompiled");
		analysis.complete(true);
		AnalysisReport report = new AnalysisReport();
		report.setFingerprint("multi-route-fixture");
		report.setFrameworkAnalysis(analysis);
		report.setComplete(true);
		return report;
	}

	private static ApplicationReportPanel overview(AtomicReference<AiEvidence> opened, List<AnalysisFinding> investigated) {
		return new ApplicationReportPanel((finding, goal) -> {
			assertThat(goal).isEqualTo(AnalysisGoal.BEHAVIOR);
			investigated.add(finding);
		}, finding -> {
		}, () -> {
		}, () -> {
		}, () -> {
		}, opened::set, () -> {
		});
	}

	private static AiEvidence evidence(String title, String code) {
		return new AiEvidence(null, title, code, AiEvidence.fingerprint(code), false);
	}

	private static JList<?> catalog(Container panel) {
		return components(panel, JList.class).stream()
				.filter(list -> WorkbenchText.str("key.list").equals(list.getAccessibleContext().getAccessibleName()))
				.findFirst().orElseThrow();
	}

	private static JComboBox<?> routeSelector(Container panel) {
		return components(panel, JComboBox.class).stream()
				.filter(combo -> WorkbenchText.str("routes.select").equals(combo.getAccessibleContext().getAccessibleName()))
				.findFirst().orElseThrow();
	}

	private static JButton button(Container panel, String key) {
		return components(panel, JButton.class).stream().filter(button -> button.getText().equals(WorkbenchText.str(key)))
				.findFirst().orElseThrow();
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
