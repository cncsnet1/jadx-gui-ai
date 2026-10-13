package jadx.gui.ai.analysis;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTriagePanelTest {
	@Test
	void allBackgroundResultsExplainHowToRestoreTheList() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AnalysisReport report = new AnalysisReport();
			report.addApi(new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "", "https://unknown.example/api", "config.json:3", null));
			ApiCatalogPanel panel = panel();
			panel.setReport(report);
			JTable table = components(panel, JTable.class).get(0);
			assertThat(table.getRowCount()).isZero();
			assertThat(components(panel, JTextArea.class))
					.anyMatch(text -> text.getText().equals(WorkbenchText.str("triage.api_folded_empty")));
			background(panel).doClick();
			assertThat(addresses(table)).containsExactly("https://unknown.example/api");
			panel.close();
		});
	}

	@Test
	void defaultViewKeepsUnknownRequestsAndExportRetainsFoldedOriginalValues() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AnalysisReport report = fixture();
			ApiCatalogPanel panel = panel();
			panel.setReport(report);
			JTable table = components(panel, JTable.class).get(0);
			JCheckBox background = background(panel);
			assertThat(background.isSelected()).isFalse();
			assertThat(addresses(table)).containsExactly("/users?token=ORIGINAL_QUERY_TOKEN", "/session");
			assertThat(components(panel, JLabel.class)).anyMatch(label -> label.getText().equals(
					String.format(WorkbenchText.str("triage.api_summary"), 2, 3, 1)));
			assertThat(components(panel, JTextArea.class)).anyMatch(text -> text.getText().contains("ORIGINAL_QUERY_TOKEN")
					&& text.getText().contains("ORIGINAL_AUTHORIZATION")
					&& text.getText().contains(TriageText.describe(EvidenceTriage.assess(report, report.getApis().get(2).asFinding()))));
			var exported = JsonParser.parseString(panel.exportJson()).getAsJsonObject();
			assertThat(exported.getAsJsonArray("endpoints")).hasSize(3);
			assertThat(exported.get("redacted").getAsBoolean()).isFalse();
			assertThat(exported.toString()).contains("BACKGROUND_ORIGINAL", "ORIGINAL_QUERY_TOKEN", "ORIGINAL_AUTHORIZATION");
			background.doClick();
			assertThat(addresses(table)).hasSize(3).contains("https://www.w3.org/2001/XMLSchema?token=BACKGROUND_ORIGINAL");
			background.doClick();
			assertThat(addresses(table)).containsExactly("/users?token=ORIGINAL_QUERY_TOKEN", "/session");
			panel.close();
		});
	}

	@Test
	void explicitEndpointNavigationRestoresBackgroundAndProjectResetRestoresDefaultView() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			AnalysisReport report = fixture();
			ApiCatalogPanel panel = panel();
			panel.setReport(report);
			JTable table = components(panel, JTable.class).get(0);
			components(panel, JTextField.class).get(0).setText("no matching source");
			components(panel, JComboBox.class).get(0).setSelectedIndex(ApiEndpoint.Origin.DECLARATION.ordinal() + 1);
			assertThat(table.getRowCount()).isZero();
			panel.selectEndpoint(report.getApis().get(0).getId());
			assertThat(background(panel).isSelected()).isTrue();
			assertThat(table.getValueAt(table.getSelectedRow(), 2)).isEqualTo(report.getApis().get(0).getAddress());
			assertThat(components(panel, JTextArea.class)).anyMatch(text -> text.getText().contains("BACKGROUND_ORIGINAL")
					&& text.getText().contains(TriageText.describe(EvidenceTriage.assess(report, report.getApis().get(0).asFinding()))));
			panel.setReport(null);
			assertThat(table.getRowCount()).isZero();
			assertThat(background(panel).isSelected()).isFalse();
			assertThat(components(panel, JTextArea.class)).allMatch(text -> !text.getText().contains("BACKGROUND_ORIGINAL"));
			panel.setReport(fixture());
			assertThat(table.getRowCount()).isEqualTo(2);
			panel.close();
		});
	}

	private static AnalysisReport fixture() {
		AnalysisReport report = new AnalysisReport();
		report.addApi(new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "",
				"https://www.w3.org/2001/XMLSchema?token=BACKGROUND_ORIGINAL", "AndroidManifest.xml:1", null));
		report.addApi(new ApiEndpoint(ApiEndpoint.Origin.CODE_CONTEXT, "POST", "/session", "a.b:12", null));
		ApiEndpoint declared = new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "GET", "/users?token=ORIGINAL_QUERY_TOKEN",
				"a.c:5", null);
		declared.header("Authorization: Bearer ORIGINAL_AUTHORIZATION");
		report.addApi(declared);
		report.setComplete(true);
		return report;
	}

	private static ApiCatalogPanel panel() {
		return new ApiCatalogPanel(source -> {
		}, endpoint -> {
		});
	}

	private static JCheckBox background(Container panel) {
		return components(panel, JCheckBox.class).stream()
				.filter(box -> box.getText().equals(WorkbenchText.str("triage.show_background"))).findFirst().orElseThrow();
	}

	private static List<String> addresses(JTable table) {
		List<String> result = new ArrayList<>();
		for (int i = 0; i < table.getRowCount(); i++) {
			result.add(table.getValueAt(i, 2).toString());
		}
		return result;
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
