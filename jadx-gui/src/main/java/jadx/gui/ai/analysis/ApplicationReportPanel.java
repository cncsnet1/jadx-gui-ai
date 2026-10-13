package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.utils.ui.WorkbenchUi;

/** The first local answer: protection, technology, visible code, then one useful next step. */
public final class ApplicationReportPanel extends JPanel {
	private final JTextArea intro = body("");
	private final JTextArea scope = body("");
	private final JPanel facts = WorkbenchUi.transparent();
	private final JPanel next = WorkbenchUi.card(16);
	private final JPanel evidence = WorkbenchUi.card(16);
	private final JScrollPane scroll = new JScrollPane();
	private final Consumer<AiEvidence> openEvidence;
	private final BiConsumer<AnalysisFinding, AnalysisGoal> investigate;
	private final Runnable showApis;
	private final Runnable showKeyCode;
	private AnalysisReport report;
	private JButton frameworkTrace;
	private boolean busy;
	private String selectedRoute = "";
	private Consumer<String> showFrameworkRoute;

	public ApplicationReportPanel(BiConsumer<AnalysisFinding, AnalysisGoal> investigate, Consumer<AnalysisFinding> inspect,
			Runnable showAll, Runnable showApis, Runnable settings) {
		this(investigate, inspect, showAll, showApis, settings, ignored -> showAll.run(), showAll);
	}

	public ApplicationReportPanel(BiConsumer<AnalysisFinding, AnalysisGoal> investigate, Consumer<AnalysisFinding> inspect,
			Runnable showAll, Runnable showApis, Runnable settings, Consumer<AiEvidence> openEvidence, Runnable showKeyCode) {
		super(new BorderLayout());
		this.openEvidence = openEvidence;
		this.investigate = investigate;
		this.showApis = showApis;
		this.showKeyCode = showKeyCode;
		this.showFrameworkRoute = ignored -> showKeyCode.run();
		setOpaque(false);
		Document content = new Document();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(18, 6, 18, 6));
		JPanel hero = WorkbenchUi.transparent();
		hero.add(WorkbenchUi.title(WorkbenchText.str("overview.heading"), 6), BorderLayout.NORTH);
		hero.add(intro);
		addSection(content, hero);
		addSection(content, next);
		facts.setLayout(new BoxLayout(facts, BoxLayout.Y_AXIS));
		addSection(content, facts);
		addSection(content, evidence);
		evidence.setVisible(false);
		JPanel footer = WorkbenchUi.transparent();
		scope.setForeground(WorkbenchUi.muted());
		footer.add(scope);
		JPanel actions = flow();
		actions.add(button("overview.all", showAll));
		footer.add(actions, BorderLayout.SOUTH);
		addSection(content, footer);
		scroll.setViewportView(content);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(18);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		add(scroll);
		setReport(null);
	}

	public void setReport(AnalysisReport value) {
		boolean newReport = value == null || report == null || !java.util.Objects.equals(value.getFingerprint(), report.getFingerprint());
		int position = newReport ? 0 : scroll.getVerticalScrollBar().getValue();
		report = value;
		intro.setText(WorkbenchText.str(value == null ? "overview.waiting"
				: value.isComplete() ? "overview.intro" : "overview.scanning"));
		facts.removeAll();
		next.removeAll();
		frameworkTrace = null;
		if (newReport) {
			evidence.setVisible(false);
			selectedRoute = "";
		}
		ApkProfile profile = value == null ? new ApkProfile() : value.getProfile();
		for (ApkProfile.Kind kind : ApkProfile.Kind.values()) {
			facts.add(factCard(profile, kind));
			facts.add(Box.createVerticalStrut(9));
		}
		buildNext(profile);
		setBusy(busy);
		if (value == null) {
			scope.setText(WorkbenchText.str("overview.coverage.boundary"));
		} else {
			scope.setText(String.format(WorkbenchText.str("overview.coverage"), profile.getDexCount(), profile.getJavaScriptCount(),
					profile.getNativeLibraryCount(), value.getApis().size()) + '\n' + value.coverage()
					+ '\n' + WorkbenchText.str("overview.coverage.boundary"));
		}
		revalidate();
		repaint();
		SwingUtilities.invokeLater(() -> {
			if (report == value) {
				scroll.getVerticalScrollBar().setValue(position);
			}
		});
	}

	private JPanel factCard(ApkProfile profile, ApkProfile.Kind kind) {
		JPanel card = WorkbenchUi.card(14);
		JPanel heading = WorkbenchUi.transparent();
		heading.add(WorkbenchUi.accentTitle(WorkbenchText.str("overview." + kind.name().toLowerCase(Locale.ROOT)), 1));
		List<ApkProfile.Fact> observations = profile.getFacts().stream().filter(f -> f.getKind() == kind)
				.collect(java.util.stream.Collectors.toList());
		JButton source = button("overview.sources", () -> showEvidence(kind, observations, profile));
		source.setEnabled(!observations.isEmpty() || !profile.getGaps().isEmpty());
		heading.add(source, BorderLayout.EAST);
		card.add(heading, BorderLayout.NORTH);
		String text = observations.isEmpty()
				? WorkbenchText.str("overview.pending")
				: observations.stream()
						.map(ApplicationReportPanel::describe).collect(java.util.stream.Collectors.joining("\n"));
		card.add(body(text));
		card.setAlignmentX(LEFT_ALIGNMENT);
		return card;
	}

	static String describe(ApkProfile.Fact fact) {
		return AiText.str("profile.fact." + fact.getCode())
				+ (fact.getValue().isEmpty() ? "" : " · " + fact.getValue()) + "  ["
				+ WorkbenchText.str("overview." + fact.getConfidence().name().toLowerCase(Locale.ROOT)) + ']';
	}

	private void buildNext(ApkProfile profile) {
		if (report != null && !report.getFrameworkAnalysis().getRoutes().isEmpty()) {
			buildRoutes(report.getFrameworkAnalysis());
			return;
		}
		if (report != null && "uni-app".equals(report.getFrameworkAnalysis().getFramework())
				&& !report.getFrameworkAnalysis().getSections().isEmpty()) {
			buildFrameworkRoute(report.getFrameworkAnalysis());
			return;
		}
		String key;
		Runnable handler;
		switch (recommendedAction(profile)) {
			case "check_protection":
				key = "protection";
				handler = this::showRecovery;
				break;
			case "inspect_javascript":
				key = "javascript";
				handler = showKeyCode;
				break;
			case "inspect_native":
				key = "native";
				handler = showKeyCode;
				break;
			default:
				key = "requests";
				handler = showApis;
				break;
		}
		next.add(WorkbenchUi.title(WorkbenchText.str("overview.next"), 1), BorderLayout.NORTH);
		next.add(body(WorkbenchText.str("overview." + key + ".detail")));
		JPanel actions = flow();
		JButton actionButton = button("overview." + (key.equals("protection") ? "protection.action" : key), handler);
		WorkbenchUi.primary(actionButton);
		actionButton.setEnabled(report != null);
		actions.add(actionButton);
		next.add(actions, BorderLayout.SOUTH);
	}

	public void setRouteNavigation(Consumer<String> navigation) {
		showFrameworkRoute = navigation;
	}

	private void buildRoutes(FrameworkAnalysis analysis) {
		List<FrameworkAnalysis.Route> routes = analysis.getRoutes();
		FrameworkAnalysis.Route selected = routes.stream().filter(route -> route.getId().equals(selectedRoute)).findFirst()
				.orElseGet(() -> routes.stream().filter(route -> !route.getFindings().isEmpty()).findFirst().orElse(routes.get(0)));
		selectedRoute = selected.getId();
		JPanel heading = WorkbenchUi.transparent();
		heading.add(WorkbenchUi.title(WorkbenchText.str("routes.title"), 1), BorderLayout.NORTH);
		JComboBox<String> choices = new JComboBox<>();
		choices.getAccessibleContext().setAccessibleName(WorkbenchText.str("routes.select"));
		routes.forEach(route -> choices.addItem(route.getName() + " · " + routeStatus(route)));
		choices.setSelectedIndex(routes.indexOf(selected));
		choices.addActionListener(event -> {
			int index = choices.getSelectedIndex();
			if (index >= 0) {
				selectedRoute = routes.get(index).getId();
				next.removeAll();
				frameworkTrace = null;
				buildRoutes(analysis);
				setBusy(busy);
				next.revalidate();
				next.repaint();
			}
		});
		heading.add(choices);
		next.add(heading, BorderLayout.NORTH);
		FrameworkAnalysis.Finding focus = selected.getFindings().stream().filter(f -> !f.getEvidence().isEmpty())
				.min(java.util.Comparator.comparingInt(f -> frameworkPriority(f.getKind()))).orElse(null);
		StringBuilder text = new StringBuilder(String.format(WorkbenchText.str("routes.summary"), routes.size(),
				selected.getResourcesRead(), selected.getFindings().size()));
		text.append('\n').append(WorkbenchText.str("routes.next." + selected.getId()));
		if (focus != null) {
			text.append("\n\n").append(WorkbenchText.str("framework.next")).append(" · ").append(focus.getTitle());
		}
		if (!selected.getGaps().isEmpty()) {
			text.append('\n').append(String.format(WorkbenchText.str("framework.gaps"), selected.getGaps().size()));
		}
		next.add(body(text.toString()));
		JPanel actions = flow();
		List<AiEvidence> available = focus == null ? selected.getEvidence() : focus.getEvidence();
		if (!available.isEmpty()) {
			JButton source = button("framework.source", () -> openEvidence.accept(available.get(0)));
			WorkbenchUi.primary(source);
			actions.add(source);
		}
		if (focus != null) {
			frameworkTrace = button("framework.trace", () -> {
				AnalysisFinding finding = ProfileLead.frameworkFinding(report, focus);
				if (finding != null && !busy && report.isComplete()) {
					investigate.accept(finding, AnalysisGoal.BEHAVIOR);
				}
			});
			frameworkTrace.setToolTipText(WorkbenchText.str("key.ai_source"));
			actions.add(frameworkTrace);
		}
		actions.add(button("framework.all", () -> showFrameworkRoute.accept(selected.getId())));
		actions.add(button("framework.coverage", () -> {
			JPanel content = evidenceContent(selected.getName() + " · " + routeStatus(selected));
			for (FrameworkAnalysis.Section section : selected.getSections()) {
				addSection(content, body(WorkbenchText.str("framework.kind." + section.getKind().name().toLowerCase(Locale.ROOT))
						+ " · " + WorkbenchText.str("framework.status." + section.getStatus().name().toLowerCase(Locale.ROOT))));
			}
			selected.getGaps().forEach(gap -> addSection(content, body(KeyCodePanel.frameworkLimitation(gap))));
			selected.getEvidence().forEach(source -> addSection(content, body(source.getTitle() + '\n' + source.getCode())));
			showEvidenceContent(content);
		}));
		next.add(actions, BorderLayout.SOUTH);
	}

	static String routeStatus(FrameworkAnalysis.Route route) {
		return WorkbenchText.str("routes.status." + route.getStatus().name().toLowerCase(Locale.ROOT));
	}

	private void buildFrameworkRoute(FrameworkAnalysis analysis) {
		next.add(WorkbenchUi.title(String.format(WorkbenchText.str("framework.title"), analysis.getFramework()), 1), BorderLayout.NORTH);
		FrameworkAnalysis.Finding focus = recommendedFrameworkFinding(analysis);
		StringBuilder text = new StringBuilder(String.format(WorkbenchText.str("framework.progress"),
				analysis.getResourcesRead(), analysis.getFindings().size()));
		text.append(" · ").append(WorkbenchText.str(analysis.isComplete() ? "framework.complete" : "framework.partial"));
		text.append('\n');
		boolean first = true;
		for (FrameworkAnalysis.Section section : analysis.getSections()) {
			if (!first) {
				text.append("   ·   ");
			}
			first = false;
			text.append(WorkbenchText.str("framework.kind." + section.getKind().name().toLowerCase(Locale.ROOT)))
					.append(" · ").append(section.getStatus() == FrameworkAnalysis.Status.FOUND
							? section.getFindings().size()
							: WorkbenchText.str("framework.status." + section.getStatus().name().toLowerCase(Locale.ROOT)));
		}
		if (!analysis.getGaps().isEmpty()) {
			text.append('\n').append(String.format(WorkbenchText.str("framework.gaps"), analysis.getGaps().size()));
		}
		if (focus != null) {
			text.append("\n\n").append(WorkbenchText.str("framework.next")).append(" · ").append(focus.getTitle());
		} else {
			text.append("\n\n").append(WorkbenchText.str("framework.empty"));
		}
		next.add(body(text.toString()));
		JPanel actions = flow();
		if (focus != null && !focus.getEvidence().isEmpty()) {
			JButton source = button("framework.source", () -> openEvidence.accept(focus.getEvidence().get(0)));
			WorkbenchUi.primary(source);
			actions.add(source);
			frameworkTrace = button("framework.trace", () -> {
				AnalysisFinding finding = ProfileLead.frameworkFinding(report, focus);
				if (finding != null && !busy && report.isComplete()) {
					investigate.accept(finding, AnalysisGoal.BEHAVIOR);
				}
			});
			frameworkTrace.setToolTipText(WorkbenchText.str("key.ai_source"));
			actions.add(frameworkTrace);
		}
		actions.add(button("framework.all", showKeyCode));
		if (!analysis.getGaps().isEmpty()) {
			actions.add(button("framework.coverage", () -> showFrameworkCoverage(analysis)));
		}
		next.add(actions, BorderLayout.SOUTH);
	}

	private void showFrameworkCoverage(FrameworkAnalysis analysis) {
		JPanel content = evidenceContent(WorkbenchText.str("overview.gaps"));
		for (String gap : analysis.getGaps()) {
			addSection(content, body(KeyCodePanel.frameworkLimitation(gap)));
		}
		for (FrameworkAnalysis.Section section : analysis.getSections()) {
			String text = WorkbenchText.str("framework.kind." + section.getKind().name().toLowerCase(Locale.ROOT)) + " · "
					+ WorkbenchText.str("framework.status." + section.getStatus().name().toLowerCase(Locale.ROOT));
			addSection(content, body(text));
		}
		showEvidenceContent(content);
	}

	static FrameworkAnalysis.Finding recommendedFrameworkFinding(FrameworkAnalysis analysis) {
		return analysis.getFindings().stream().filter(finding -> !finding.getEvidence().isEmpty())
				.min(java.util.Comparator.comparingInt(finding -> frameworkPriority(finding.getKind()))).orElse(null);
	}

	static int frameworkPriority(FrameworkAnalysis.Kind kind) {
		switch (kind) {
			case REQUESTS:
				return 0;
			case INTERCEPTORS:
				return 1;
			case AUTH_STORAGE:
			case CRYPTO:
				return 2;
			case PAGES:
				return 3;
			case NATIVE_BRIDGE:
				return 4;
			case CONFIGURATION:
				return 5;
			default:
				return 6;
		}
	}

	static String recommendedAction(ApkProfile profile) {
		List<String> actions = profile.getRecommendations();
		if (actions.contains("check_protection")) {
			return "check_protection";
		}
		if (actions.contains("inspect_javascript")) {
			return "inspect_javascript";
		}
		if (actions.contains("inspect_native") && (profile.getDexCount() == 0
				|| profile.getFacts().stream().anyMatch(f -> f.getCode().equals("technology_flutter")))) {
			return "inspect_native";
		}
		return "inspect_requests";
	}

	private void showEvidence(ApkProfile.Kind kind, List<ApkProfile.Fact> observations, ApkProfile profile) {
		JPanel content = evidenceContent(WorkbenchText.str("overview." + kind.name().toLowerCase(Locale.ROOT)));
		for (ApkProfile.Fact fact : observations) {
			addSection(content, body(describe(fact)));
			if (fact.getEvidence().isEmpty()) {
				addSection(content, body(WorkbenchText.str("overview.no_sources")));
			}
			for (AiEvidence source : fact.getEvidence()) {
				JPanel item = WorkbenchUi.transparent();
				item.add(body(source.getTitle() + '\n' + source.getCode()));
				JPanel actions = flow();
				actions.add(button("overview.source", () -> openEvidence.accept(source)));
				item.add(actions, BorderLayout.SOUTH);
				addSection(content, item);
			}
		}
		if (!profile.getGaps().isEmpty()) {
			addSection(content, body(WorkbenchText.str("overview.gaps") + '\n' + profile.getGaps().stream()
					.map(ApplicationReportPanel::describeGap).collect(java.util.stream.Collectors.joining("\n"))));
		}
		showEvidenceContent(content);
	}

	static String describeGap(String gap) {
		int separator = gap.indexOf(':');
		String key = separator < 0 ? gap : gap.substring(0, separator);
		try {
			return AiText.str("profile.limitation." + key) + (separator < 0 ? "" : gap.substring(separator));
		} catch (java.util.MissingResourceException e) {
			return gap;
		}
	}

	private void showRecovery() {
		JPanel content = evidenceContent(WorkbenchText.str("overview.recovery"));
		addSection(content, body(WorkbenchText.str("overview.recovery.detail")));
		if (report != null) {
			for (ApkProfile.Fact fact : report.getProfile().getFacts()) {
				if (fact.getKind() == ApkProfile.Kind.PROTECTION) {
					addSection(content, body(describe(fact)));
					for (AiEvidence source : fact.getEvidence()) {
						addSection(content, body(source.getTitle() + '\n' + source.getCode()));
					}
				}
			}
		}
		JPanel actions = flow();
		actions.add(button("overview.requests", showApis));
		actions.add(button("key.title", showKeyCode));
		addSection(content, actions);
		showEvidenceContent(content);
	}

	private JPanel evidenceContent(String title) {
		JPanel content = WorkbenchUi.transparent();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		JPanel header = WorkbenchUi.transparent();
		header.add(WorkbenchUi.title(title, 1));
		header.add(button("overview.close", () -> evidence.setVisible(false)), BorderLayout.EAST);
		addSection(content, header);
		return content;
	}

	private void showEvidenceContent(JPanel content) {
		evidence.removeAll();
		evidence.add(content);
		evidence.setVisible(true);
		revalidate();
		repaint();
		SwingUtilities.invokeLater(() -> evidence.scrollRectToVisible(new java.awt.Rectangle(0, 0, evidence.getWidth(), 80)));
	}

	public void setBusy(boolean value) {
		busy = value;
		if (frameworkTrace != null) {
			frameworkTrace.setEnabled(!busy && report != null && report.isComplete());
		}
		// Local evidence and navigation stay usable while an optional AI investigation runs.
	}

	static void addSection(JPanel parent, JComponent child) {
		child.setAlignmentX(LEFT_ALIGNMENT);
		parent.add(child);
		parent.add(Box.createVerticalStrut(12));
	}

	static JTextArea body(String text) {
		JTextArea area = new JTextArea(text) {
			@Override
			public void scrollRectToVisible(java.awt.Rectangle rectangle) {
				// Static copy must not move the document during layout or theme changes.
			}
		};
		area.setEditable(false);
		area.setFocusable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setFont(javax.swing.UIManager.getFont("Label.font"));
		((javax.swing.text.DefaultCaret) area.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
		return area;
	}

	private static JButton button(String key, Runnable action) {
		JButton button = new JButton(WorkbenchText.str(key));
		button.addActionListener(e -> action.run());
		return button;
	}

	private static JPanel flow() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
		panel.setOpaque(false);
		return panel;
	}

	static final class Document extends JPanel implements Scrollable {
		Document() {
			setOpaque(false);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return new Dimension(650, 500);
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
			return 18;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
			return 180;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}
}
