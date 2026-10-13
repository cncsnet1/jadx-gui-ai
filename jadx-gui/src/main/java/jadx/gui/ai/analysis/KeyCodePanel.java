package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.utils.ui.WorkbenchUi;

/** Local code and ELF observations, with optional investigations attached to a concrete source. */
public final class KeyCodePanel extends JPanel {
	private static final String[] GROUPS =
			{ "all", "framework", "requests", "crypto", "identity", "config", "entry", "javascript", "native" };
	private static final int SYMBOL_PREVIEW = 40;
	private final BiConsumer<AnalysisFinding, AnalysisGoal> investigate;
	private final Consumer<AiEvidence> openEvidence;
	private final JComboBox<String> filter = new JComboBox<>();
	private final JComboBox<String> routeFilter = new JComboBox<>();
	private final JCheckBox background = new JCheckBox(WorkbenchText.str("triage.show_background"));
	private List<String> routeIds = List.of("");
	private int folded;
	private final DefaultListModel<Entry> model = new DefaultListModel<>();
	private final JList<Entry> list = new JList<>(model);
	private final JComboBox<AiEvidence> sources = new JComboBox<>();
	private final JTextArea detail = new JTextArea();
	private final JPanel summary = WorkbenchUi.card(14);
	private final JLabel count = WorkbenchUi.mutedLabel("");
	private final JLabel title = WorkbenchUi.title("", 2);
	private final JButton trace = new JButton(WorkbenchText.str("key.trace"));
	private final JButton source = new JButton(WorkbenchText.str("key.source"));
	private AnalysisReport report;
	private AnalysisFinding selectedFinding;
	private boolean busy;
	private boolean updating;

	public KeyCodePanel(BiConsumer<AnalysisFinding, AnalysisGoal> investigate, Consumer<AiEvidence> openEvidence, Runnable showAll) {
		super(new BorderLayout(10, 10));
		this.investigate = investigate;
		this.openEvidence = openEvidence;
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(14, 6, 6, 6));
		JPanel heading = WorkbenchUi.transparent();
		heading.add(ApplicationReportPanel.body(WorkbenchText.str("key.intro")));
		JButton all = new JButton(WorkbenchText.str("overview.all"));
		all.addActionListener(e -> showAll.run());
		heading.add(all, BorderLayout.EAST);
		add(heading, BorderLayout.NORTH);

		JPanel catalog = WorkbenchUi.transparent();
		for (String group : GROUPS) {
			filter.addItem(WorkbenchText.str("key." + group));
		}
		filter.getAccessibleContext().setAccessibleName(WorkbenchText.str("key.filter"));
		filter.addActionListener(e -> refresh());
		JPanel filters = WorkbenchUi.transparent();
		filters.setLayout(new BoxLayout(filters, BoxLayout.Y_AXIS));
		filters.add(filter);
		routeFilter.getAccessibleContext().setAccessibleName(WorkbenchText.str("routes.select"));
		routeFilter.addActionListener(e -> {
			if (!updating) {
				refresh();
			}
		});
		filters.add(routeFilter);
		background.setOpaque(false);
		background.addActionListener(e -> refresh());
		filters.add(background);
		catalog.add(filters, BorderLayout.NORTH);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(64);
		list.setFixedCellWidth(240);
		list.getAccessibleContext().setAccessibleName(WorkbenchText.str("key.list"));
		JPanel row = new JPanel(new BorderLayout(0, 5));
		row.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
		JLabel name = WorkbenchUi.title("", 0);
		JLabel location = WorkbenchUi.mutedLabel("");
		row.add(name, BorderLayout.NORTH);
		row.add(location);
		list.setCellRenderer((view, value, index, selected, focused) -> {
			row.setBackground(selected ? WorkbenchUi.tint() : view.getBackground());
			name.setText(value.title());
			name.setForeground(view.getForeground());
			location.setText(value.location());
			row.setToolTipText(value.location());
			return row;
		});
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && !updating) {
				render();
			}
		});
		catalog.add(new JScrollPane(list));
		catalog.add(count, BorderLayout.SOUTH);

		JPanel inspection = WorkbenchUi.transparent();
		JPanel context = WorkbenchUi.transparent();
		context.add(title, BorderLayout.NORTH);
		context.add(summary);
		inspection.add(context, BorderLayout.NORTH);
		detail.setEditable(false);
		detail.setLineWrap(true);
		detail.setWrapStyleWord(true);
		detail.setFont(UIManager.getFont("Label.font"));
		detail.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
		((javax.swing.text.DefaultCaret) detail.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
		inspection.add(new JScrollPane(detail));
		JPanel bottom = WorkbenchUi.transparent();
		sources.getAccessibleContext().setAccessibleName(WorkbenchText.str("key.sources"));
		sources.setRenderer(new DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> view, Object value, int index,
					boolean selected, boolean focused) {
				super.getListCellRendererComponent(view, value, index, selected, focused);
				putClientProperty("html.disable", true);
				return this;
			}
		});
		sources.setPrototypeDisplayValue(new AiEvidence(null, "classes.dex / com.example.Business.method", "", "", false));
		bottom.add(sources, BorderLayout.NORTH);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
		actions.setOpaque(false);
		trace.addActionListener(e -> traceSelected());
		trace.setToolTipText(WorkbenchText.str("key.ai_source"));
		WorkbenchUi.primary(trace);
		source.addActionListener(e -> {
			AiEvidence selected = (AiEvidence) sources.getSelectedItem();
			if (selected != null) {
				openEvidence.accept(selected);
			}
		});
		actions.add(trace);
		actions.add(source);
		bottom.add(actions);
		inspection.add(bottom, BorderLayout.SOUTH);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, catalog, inspection);
		split.setBorder(null);
		split.setResizeWeight(0.30);
		split.setDividerLocation(290);
		catalog.setMinimumSize(new Dimension(180, 150));
		inspection.setMinimumSize(new Dimension(280, 150));
		add(split);
		setReport(null);
	}

	public void setReport(AnalysisReport value) {
		boolean changedProject = report != null && value != null
				&& !java.util.Objects.equals(report.getFingerprint(), value.getFingerprint());
		report = value;
		String route = selectedRoute();
		updating = true;
		routeFilter.removeAllItems();
		routeFilter.addItem(WorkbenchText.str("routes.all"));
		List<String> ids = new java.util.ArrayList<>(List.of(""));
		if (value != null) {
			value.getFrameworkAnalysis().getRoutes().forEach(item -> {
				ids.add(item.getId());
				routeFilter.addItem(item.getName());
			});
		}
		routeIds = ids;
		routeFilter.setSelectedIndex(changedProject || value == null ? 0 : Math.max(0, routeIds.indexOf(route)));
		routeFilter.setVisible(routeIds.size() > 1);
		updating = false;
		if (changedProject || value == null) {
			background.setSelected(false);
			filter.setSelectedIndex(0);
			list.clearSelection();
		}
		refresh();
	}

	public void selectRoute(String id) {
		routeFilter.setSelectedIndex(Math.max(0, routeIds.indexOf(id)));
		selectCategory("framework");
	}

	private String selectedRoute() {
		int index = routeFilter.getSelectedIndex();
		return index < 0 || index >= routeIds.size() ? "" : routeIds.get(index);
	}

	/** Used by the overview recommendation to open the matching local inventory directly. */
	public void selectCategory(String group) {
		for (int i = 0; i < GROUPS.length; i++) {
			if (GROUPS[i].equals(group)) {
				filter.setSelectedIndex(i);
				return;
			}
		}
	}

	private void refresh() {
		String selectedId = list.getSelectedValue() == null ? null : list.getSelectedValue().id();
		updating = true;
		model.clear();
		folded = 0;
		String selectedGroup = GROUPS[Math.max(0, filter.getSelectedIndex())];
		if (report != null) {
			report.getFrameworkAnalysis().getFindings().stream()
					.filter(finding -> selectedRoute().isEmpty() || finding.getRouteId().equals(selectedRoute()))
					.filter(finding -> selectedGroup.equals("all") || selectedGroup.equals("framework")
							|| frameworkGroup(finding.getKind()).equals(selectedGroup))
					.sorted(Comparator.comparingInt(finding -> ApplicationReportPanel.frameworkPriority(finding.getKind())))
					.forEach(finding -> addEntry(new Entry(finding), false));
			EvidenceTriage.ranked(report, AnalysisGoal.BEHAVIOR, report.getFindings()).stream()
					.filter(f -> !f.getKind().group().equals("network"))
					.filter(f -> selectedRoute().isEmpty() || selectedRoute().equals("android"))
					.filter(f -> selectedGroup.equals("all") || group(f).equals(selectedGroup))
					.forEach(f -> addEntry(new Entry(f, null, null), false));
			if (selectedGroup.equals("all") || selectedGroup.equals("javascript")) {
				Map<String, AiEvidence> scripts = new LinkedHashMap<>();
				report.getProfile().getFacts().stream().filter(fact -> fact.getCode().equals("visibility_javascript"))
						.flatMap(fact -> fact.getEvidence().stream()).limit(128)
						.forEach(evidence -> scripts.putIfAbsent(evidence.getTitle(), evidence));
				scripts.values().stream().filter(this::inRoute).forEach(evidence -> addEntry(new Entry(null, null, evidence),
						selectedGroup.equals("javascript")));
			}
			if (selectedGroup.equals("all") || selectedGroup.equals("native")) {
				report.getProfile().getNativeLibraries().stream().filter(library -> library.getEvidence().stream().anyMatch(this::inRoute))
						.forEach(library -> addEntry(new Entry(null, library, null), selectedGroup.equals("native")));
			}
		}
		int selectedIndex = -1;
		for (int i = 0; i < model.size(); i++) {
			if (model.get(i).id().equals(selectedId)) {
				selectedIndex = i;
				break;
			}
		}
		if (!model.isEmpty()) {
			list.setSelectedIndex(selectedIndex < 0 ? 0 : selectedIndex);
		}
		long findings = java.util.stream.IntStream.range(0, model.size())
				.filter(i -> model.get(i).finding != null || model.get(i).framework != null).count();
		long libraries = java.util.stream.IntStream.range(0, model.size()).filter(i -> model.get(i).library != null).count();
		count.setText(String.format(WorkbenchText.str("key.count"), findings, libraries, model.size() - findings - libraries)
				+ (folded == 0 ? "" : " · " + String.format(WorkbenchText.str("triage.folded"), folded)));
		updating = false;
		render();
	}

	private boolean inRoute(AiEvidence evidence) {
		return selectedRoute().isEmpty() || report.getFrameworkAnalysis().getRoutes().stream()
				.filter(route -> route.getId().equals(selectedRoute()))
				.flatMap(route -> java.util.stream.Stream.concat(route.getEvidence().stream(),
						route.getFindings().stream().flatMap(finding -> finding.getEvidence().stream())))
				.anyMatch(source -> InvestigationScope.sameSource(source, evidence));
	}

	private void addEntry(Entry entry, boolean explicitInventory) {
		AnalysisFinding finding = entry.framework != null ? ProfileLead.frameworkFinding(report, entry.framework)
				: entry.finding != null ? entry.finding
				: entry.library != null ? ProfileLead.nativeFinding(report, entry.library)
				: ProfileLead.scriptFinding(report, entry.resource);
		if (!background.isSelected() && !explicitInventory && finding != null
				&& EvidenceTriage.assess(report, finding).getTier() == EvidenceTriage.Tier.BACKGROUND) {
			folded++;
		} else {
			model.addElement(entry);
		}
	}

	private void render() {
		Entry selected = list.getSelectedValue();
		selectedFinding = null;
		sources.removeAllItems();
		summary.removeAll();
		summary.setVisible(selected != null);
		if (selected == null) {
			title.setText(WorkbenchText.str("key.title"));
			detail.setText(WorkbenchText.str(report == null ? "key.waiting" : "key.empty"));
			if (report != null && !selectedRoute().isEmpty()) {
				report.getFrameworkAnalysis().getRoutes().stream().filter(route -> route.getId().equals(selectedRoute())).findFirst()
						.ifPresent(route -> {
							title.setText(route.getName() + " · " + ApplicationReportPanel.routeStatus(route));
							StringBuilder text = new StringBuilder(WorkbenchText.str("routes.next." + route.getId()));
							route.getGaps().forEach(gap -> text.append('\n').append(frameworkLimitation(gap)));
							route.getEvidence().forEach(e -> {
								sources.addItem(e);
								text.append("\n\n").append(e.getTitle()).append('\n').append(e.getCode());
							});
							detail.setText(text.toString());
						});
			}
		} else {
			title.setText(selected.title());
			selected.evidence().forEach(sources::addItem);
			if (selected.framework != null) {
				selectedFinding = ProfileLead.frameworkFinding(report, selected.framework);
				renderFramework(selected.framework);
			} else if (selected.finding != null) {
				selectedFinding = selected.finding;
				renderFinding(selected.finding);
			} else if (selected.library != null) {
				selectedFinding = ProfileLead.nativeFinding(report, selected.library);
				trace.setText(WorkbenchText.str("key.trace.native"));
				renderNative(selected.library);
			} else {
				selectedFinding = ProfileLead.scriptFinding(report, selected.resource);
				trace.setText(WorkbenchText.str("key.trace.javascript"));
				summary.add(ApplicationReportPanel.body(WorkbenchText.str("key.javascript.summary")));
				detail.setText(selected.resource.getTitle() + '\n' + selected.resource.getCode());
			}
		}
		if (selectedFinding != null) {
			EvidenceTriage.Decision decision = EvidenceTriage.assess(report, selectedFinding);
			JLabel triage = WorkbenchUi.mutedLabel(TriageText.summary(decision));
			triage.setToolTipText(TriageText.describe(decision));
			triage.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
			summary.add(triage, BorderLayout.SOUTH);
			detail.setText(TriageText.describe(decision) + "\n\n" + detail.getText());
		}
		detail.setCaretPosition(0);
		setBusy(busy);
		revalidate();
		repaint();
	}

	private void renderFramework(FrameworkAnalysis.Finding finding) {
		JPanel content = WorkbenchUi.transparent();
		content.add(WorkbenchUi.accentTitle(WorkbenchText.str("framework.local"), 0), BorderLayout.NORTH);
		StringBuilder explanation = new StringBuilder(WorkbenchText.str("framework.description."
				+ finding.getKind().name().toLowerCase(Locale.ROOT)));
		List<FrameworkAnalysis.Section> sections = report.getFrameworkAnalysis().getRoutes().stream()
				.filter(route -> route.getId().equals(finding.getRouteId())).findFirst().map(FrameworkAnalysis.Route::getSections)
				.orElse(report.getFrameworkAnalysis().getSections());
		for (FrameworkAnalysis.Section section : sections) {
			if (section.getKind() == finding.getKind() && !section.getLimitations().isEmpty()) {
				explanation.append("\n\n").append(WorkbenchText.str("overview.gaps"));
				section.getLimitations().forEach(value -> explanation.append('\n').append(frameworkLimitation(value)));
			}
		}
		content.add(ApplicationReportPanel.body(explanation.toString()));
		summary.add(content);
		StringBuilder text = new StringBuilder(finding.getTitle());
		List<String> gaps = report.getFrameworkAnalysis().getRoutes().stream()
				.filter(route -> route.getId().equals(finding.getRouteId())).findFirst().map(FrameworkAnalysis.Route::getGaps)
				.orElse(report.getFrameworkAnalysis().getGaps());
		if (!gaps.isEmpty()) {
			text.append("\n\n").append(WorkbenchText.str("overview.gaps"));
			gaps.forEach(value -> text.append('\n').append(frameworkLimitation(value)));
		}
		for (AiEvidence evidence : finding.getEvidence()) {
			text.append("\n\n").append(evidence.getTitle()).append('\n').append(evidence.getCode());
		}
		detail.setText(text.toString());
		trace.setText(WorkbenchText.str("framework.trace"));
	}

	static String frameworkLimitation(String value) {
		try {
			return WorkbenchText.str("framework.limitation." + value);
		} catch (java.util.MissingResourceException ignored) {
			return value;
		}
	}

	private void renderFinding(AnalysisFinding finding) {
		JPanel content = WorkbenchUi.transparent();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.add(WorkbenchUi.accentTitle(WorkbenchText.str("key.static"), 0));
		if (finding.getKind() == AnalysisFinding.Kind.CRYPTO) {
			CryptoSummary crypto = CryptoSummary.from(finding.getEvidence());
			addInsight(content, "key.crypto.algorithms", values(crypto.getAlgorithmCandidates()));
			addInsight(content, "key.crypto.references", values(crypto.getReferences()));
			addInsight(content, "key.crypto.keys", WorkbenchText.str("key.crypto.keys.unknown"));
			addInsight(content, "key.crypto.inputs", WorkbenchText.str("key.crypto.inputs.unknown"));
			JTextArea boundary = ApplicationReportPanel.body(WorkbenchText.str("key.crypto.candidates"));
			boundary.setForeground(WorkbenchUi.muted());
			content.add(boundary);
		} else {
			addInsight(content, "key.meaning", finding.getKind().text("why"));
			addInsight(content, "key.unknown", finding.getKind().text("gap"));
		}
		summary.add(content);
		StringBuilder text = new StringBuilder(finding.getId()).append(" · ").append(finding.getValue());
		text.append("\n\n").append(WorkbenchText.str("key.evidence"));
		for (AiEvidence evidence : finding.getEvidence()) {
			text.append("\n\n").append(evidence.getTitle()).append('\n').append(evidence.getCode());
		}
		detail.setText(text.toString());
		trace.setText(WorkbenchText.str("key.trace." + group(finding)));
	}

	private static void addInsight(JPanel parent, String key, String value) {
		JPanel row = WorkbenchUi.transparent();
		row.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		row.add(WorkbenchUi.title(WorkbenchText.str(key), 0), BorderLayout.NORTH);
		row.add(ApplicationReportPanel.body(value));
		parent.add(row);
	}

	private static String values(List<String> values) {
		return values.isEmpty() ? WorkbenchText.str("key.crypto.unknown") : String.join(" · ", values);
	}

	private void renderNative(ApkProfile.NativeLibrary library) {
		JPanel content = WorkbenchUi.transparent();
		content.add(WorkbenchUi.accentTitle(WorkbenchText.str("key.native.summary"), 0), BorderLayout.NORTH);
		content.add(ApplicationReportPanel.body(library.getAbi() + " · " + library.getArchitecture() + " · "
				+ (library.getBitness() == 0 ? "?" : library.getBitness()) + " bit\n"
				+ WorkbenchText.str(library.isElf() ? "key.native.valid" : "key.native.invalid") + '\n'
				+ WorkbenchText.str("key.native.boundary")));
		summary.add(content);
		StringBuilder text = new StringBuilder(WorkbenchText.str("key.native.path")).append(": ").append(library.getPath());
		appendSymbols(text, "key.native.jni", library.getJniSymbols());
		appendSymbols(text, "key.native.exports", library.getExportedSymbols());
		appendSymbols(text, "key.native.imports", library.getImportedSymbols());
		if (!library.getLimitations().isEmpty()) {
			text.append("\n\n").append(WorkbenchText.str("key.native.limitations"));
			library.getLimitations().forEach(value -> text.append('\n').append(AiText.str("profile.limitation." + value)));
		}
		for (AiEvidence evidence : library.getEvidence()) {
			text.append("\n\n").append(evidence.getTitle()).append('\n').append(evidence.getCode());
		}
		detail.setText(text.toString());
	}

	private static void appendSymbols(StringBuilder text, String key, List<String> symbols) {
		text.append("\n\n").append(WorkbenchText.str(key)).append('\n');
		if (symbols.isEmpty()) {
			text.append(WorkbenchText.str("key.native.none"));
		} else {
			symbols.stream().limit(SYMBOL_PREVIEW).forEach(symbol -> text.append(symbol).append('\n'));
			if (symbols.size() > SYMBOL_PREVIEW) {
				text.append(String.format(WorkbenchText.str("key.native.more"), SYMBOL_PREVIEW));
			}
		}
	}

	public void setBusy(boolean value) {
		busy = value;
		boolean finding = selectedFinding != null;
		trace.setVisible(finding);
		trace.setEnabled(finding && !busy && report != null && report.isComplete());
		source.setEnabled(sources.getItemCount() > 0);
		sources.setEnabled(sources.getItemCount() > 0);
	}

	private void traceSelected() {
		if (selectedFinding != null && !busy && report != null && report.isComplete()) {
			AnalysisFinding finding = selectedFinding;
			AnalysisGoal goal = finding.getKind() == AnalysisFinding.Kind.LOGIN ? AnalysisGoal.LOGIN
					: finding.getKind().group().equals("secret") ? AnalysisGoal.SECRETS : AnalysisGoal.BEHAVIOR;
			investigate.accept(finding, goal);
		}
	}

	/**
	 * Passed to the investigation runner as a focused task, never as a finding or a verified result.
	 */
	public static String investigationDirection(AnalysisFinding finding) {
		if (finding.getKind() == AnalysisFinding.Kind.FRAMEWORK_CODE) {
			return WorkbenchText.str("key.direction.framework");
		}
		String direction = finding.getKind() == AnalysisFinding.Kind.APK_STRUCTURE ? ProfileLead.directionKind(finding) : group(finding);
		return WorkbenchText.str("key.direction." + direction);
	}

	public static String investigationDirection(AnalysisReport report, AnalysisFinding finding) {
		if (finding.getKind() == AnalysisFinding.Kind.FRAMEWORK_CODE) {
			String task = ProfileLead.frameworkTask(report, finding);
			return task == null || task.isBlank() ? WorkbenchText.str("key.direction.framework") : task;
		}
		return investigationDirection(finding);
	}

	static String frameworkGroup(FrameworkAnalysis.Kind kind) {
		switch (kind) {
			case CRYPTO:
				return "crypto";
			case REQUESTS:
			case INTERCEPTORS:
				return "requests";
			case AUTH_STORAGE:
				return "identity";
			case CONFIGURATION:
				return "config";
			case PAGES:
				return "entry";
			case NATIVE_BRIDGE:
				return "native";
			default:
				return "javascript";
		}
	}

	static String group(AnalysisFinding finding) {
		switch (finding.getKind()) {
			case CRYPTO:
				return "crypto";
			case LOGIN:
				return "identity";
			case PRIVATE_KEY:
			case CREDENTIAL:
			case TOKEN:
			case DEBUGGABLE:
			case BACKUP:
			case PUBLIC_CONFIG:
				return "config";
			default:
				return "entry";
		}
	}

	private static final class Entry {
		private final AnalysisFinding finding;
		private final ApkProfile.NativeLibrary library;
		private final AiEvidence resource;
		private final FrameworkAnalysis.Finding framework;

		private Entry(AnalysisFinding finding, ApkProfile.NativeLibrary library, AiEvidence resource) {
			this.finding = finding;
			this.library = library;
			this.resource = resource;
			this.framework = null;
		}

		private Entry(FrameworkAnalysis.Finding framework) {
			this.finding = null;
			this.library = null;
			this.resource = null;
			this.framework = framework;
		}

		private String id() {
			if (framework != null) {
				return "framework:" + framework.getId();
			}
			return finding != null ? finding.getId() : library != null ? "native:" + library.getPath() : "script:" + resource.getTitle();
		}

		private String title() {
			if (framework != null) {
				return WorkbenchText.str("framework.kind." + framework.getKind().name().toLowerCase(Locale.ROOT))
						+ " · " + framework.getTitle();
			}
			if (finding != null) {
				return finding.getId() + " · " + finding.getTitle();
			}
			String path = library != null ? library.getPath() : resource.getTitle();
			return path.substring(path.lastIndexOf('/') + 1) + " · "
					+ (library != null ? library.getAbi() : WorkbenchText.str("key.javascript"));
		}

		private String location() {
			if (framework != null) {
				return framework.getEvidence().isEmpty() ? framework.getSummary() : framework.getEvidence().get(0).getTitle();
			}
			return finding == null ? library != null ? library.getPath() : resource.getTitle()
					: finding.getEvidence().isEmpty() ? finding.getValue() : finding.getEvidence().get(0).getTitle();
		}

		private List<AiEvidence> evidence() {
			if (framework != null) {
				return framework.getEvidence();
			}
			return finding == null ? library != null ? library.getEvidence() : List.of(resource) : finding.getEvidence();
		}
	}
}
