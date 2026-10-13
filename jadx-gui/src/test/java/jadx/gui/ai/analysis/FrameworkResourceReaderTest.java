package jadx.gui.ai.analysis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiClient;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiProfile;
import jadx.gui.ai.fixtures.AnalysisFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameworkResourceReaderTest {

	@TempDir
	Path temporary;

	@Test
	void pagesOriginalTextWithoutLosingBoundaryContentAndRejectsUnknownTargets() throws Exception {
		String source = "const token = 'Bearer original-value';\n" + "x".repeat(6050) + "\nuni.request('/tail');";
		ResourceFile file = file("app.js", source);
		FrameworkResourceReader.Entry entry = new FrameworkResourceReader.Entry("fixture.apk!/assets/app.js", file);
		List<FrameworkResourceReader.Entry> catalog = List.of(entry);
		var first = FrameworkResourceReader.read(catalog, entry.id, new AiCancellation());
		assertThat(first.evidence.getCode()).contains("Bearer original-value").hasSize(6000);
		assertThat(first.evidence.isTruncated()).isTrue();
		assertThat(first.evidence.getTitle()).isEqualTo("fixture.apk!/assets/app.js:1:0");
		assertThat(first.next).isEqualTo(entry.id + "@6000");
		var second = FrameworkResourceReader.read(catalog, first.next, new AiCancellation());
		assertThat(second.evidence.getTitle()).isEqualTo("fixture.apk!/assets/app.js:2:6000");
		assertThat(first.evidence.getCode() + second.evidence.getCode()).isEqualTo(source);
		assertThat(second.next).isEmpty();
		for (String target : List.of(file.getOriginalName(), "../app.js", "R-0000000000000000", entry.id + "@1048576")) {
			assertThatThrownBy(() -> FrameworkResourceReader.read(catalog, target, new AiCancellation()))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void catalogAndAgentReadUseOnlyLoadedTextAndPreserveFrameworkOwnership() throws Exception {
		ResourceFile js = file("business.js", "uni.request({url:'/login?token=raw-query',header:{Authorization:'raw-header'}});");
		ResourceFile so = file("libapp.so", "binary");
		ResourceFile json = file("manifest.json", "{\"appid\":\"__UNI__123\"}");
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(Path.of(js.getOriginalName()).toFile(), Path.of(json.getOriginalName()).toFile(),
				Path.of(so.getOriginalName()).toFile(),
				Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile()));
		args.setUseDxInput(false);
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			AnalysisReport report = new AnalysisReport();
			FrameworkAnalysis framework = new FrameworkAnalysis();
			framework.framework("uni-app");
			framework.add(new FrameworkAnalysis.Finding(FrameworkAnalysis.Kind.REQUESTS, "Request wrapper", "Observed request",
					"Read business.js and continue through Authorization", new AiEvidence(null, js.getOriginalName() + ":1:0",
							"uni.request", "source", false)));
			report.setFrameworkAnalysis(framework);
			var catalog = FrameworkResourceReader.catalog(decompiler, report);
			assertThat(catalog).extracting(e -> e.resource.getOriginalName())
					.containsExactly(js.getOriginalName(), json.getOriginalName());
			AnalysisFinding finding = ProfileLead.frameworkFinding(report, framework.getFindings().get(0));
			InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.NETWORK, "Trace request", finding.getId());
			run.select(List.of(finding));
			LocalAnalysisTools tools = new LocalAnalysisTools(decompiler);
			assertThat(tools.catalog(run, report)).contains("uni-app", catalog.get(0).id, "Authorization");
			String result = tools.execute("read_resource", catalog.get(0).id, "", run, report, new AiCancellation());
			assertThat(result).contains("raw-query", "raw-header", "uni.request");
			assertThat(run.getEvidence().entries()).hasSize(1);
			assertThat(run.getEvidence().entries().get(0).getSource().getCode()).contains("raw-query", "raw-header");
			assertThat(run.evidenceOwners(run.getEvidence().entries().get(0).getId())).isEqualTo(Set.of(finding.getId()));

			report.setFingerprint("fixture");
			report.setEngineProfile(InvestigationService.profile(decompiler));
			report.setComplete(true);
			AtomicInteger calls = new AtomicInteger();
			AiClient client = (model, messages, temperature, budget, token, output) -> {
				int turn = calls.incrementAndGet();
				if (turn == 1) {
					assertThat(messages.toString()).contains("uni-app", "read_resource", catalog.get(0).id);
					return "{\"calls\":[{\"operation\":\"read_resource\",\"target\":\"" + catalog.get(0).id + "\"}]}";
				}
				assertThat(messages.toString()).contains("raw-query", "raw-header", "uni.request");
				return turn == 2 ? "{\"calls\":[],\"questions\":[]}" : "Observed uni-app request with original header [S2].";
			};
			InvestigationRun investigated = new InvestigationService(decompiler, client).investigate(report, AnalysisGoal.NETWORK,
					ProfileLead.frameworkTask(report, finding), finding, null, new AiProfile(), new AiCancellation(), ignored -> {
					}, ignored -> {
					});
			assertThat(investigated.getState()).isEqualTo(InvestigationRun.State.COMPLETED);
			assertThat(investigated.getCalls()).isEqualTo(1);
			assertThat(calls).hasValue(3);
			assertThat(investigated.getNodes()).isEmpty();
			assertThat(investigated.getAnswer().getEvidence()).anySatisfy(e -> assertThat(e.getCode()).contains("raw-header"));
			report.putInvestigation(investigated);
			AnalysisReport restored = new Gson().fromJson(new Gson().toJson(report), AnalysisReport.class);
			assertThat(restored.markdown()).contains("raw-header", "raw-query");
			assertThat(
					InvestigationService.canResume(restored, restored.getInvestigations().get(0), InvestigationService.profile(decompiler)))
							.isTrue();
		}
	}

	@Test
	void selectedApiFindingAndProfileScriptBeyondInitialCatalogRemainReadable() throws Exception {
		try (JadxDecompiler decompiler = projectWithResources(160)) {
			AnalysisReport report = new AnalysisReport();
			String title = "many-resources.jar!/assets/r159.js";
			AiEvidence evidence = new AiEvidence(null, title + ":1:0", "const token = 'original-159';", "fixture", false);
			ApiEndpoint api = new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "GET", "/tail", evidence.getTitle(), null);
			api.source(evidence);
			report.addApi(api);
			AnalysisFinding ordinary = new AnalysisFinding("F1", AnalysisFinding.Kind.TOKEN, "original-159", evidence);
			report.add(ordinary);
			ApkProfile profile = new ApkProfile();
			profile.fact(ApkProfile.Kind.VISIBILITY, "visibility_javascript", "1", ApkProfile.Confidence.OBSERVED, List.of(evidence));
			report.setProfile(profile);
			List<FrameworkResourceReader.Entry> initial = FrameworkResourceReader.catalog(decompiler, report);
			assertThat(initial).hasSize(128).noneSatisfy(entry -> assertThat(entry.title).isEqualTo(title));
			LocalAnalysisTools tools = new LocalAnalysisTools(decompiler);
			for (AnalysisFinding selected : List.of(api.asFinding(), ordinary, ProfileLead.scriptFinding(report, evidence))) {
				InvestigationRun run =
						new InvestigationRun("fixture", "profile", AnalysisGoal.BEHAVIOR, "Read selected source", selected.getId());
				run.select(List.of(selected));
				String id = "R-" + AiEvidence.fingerprint(title).substring(0, 16);
				assertThat(tools.catalog(run, report)).contains(id, title);
				assertThat(tools.execute("read_resource", id, "", run, report, new AiCancellation())).contains("original-159");
				assertThat(run.evidenceOwners(run.getEvidence().entries().get(0).getId())).containsExactly(selected.getId());
			}
		}
	}

	@Test
	void searchingLoadedNamesRegistersResourcesBeyondInitialCatalogWithABoundedRegistry() throws Exception {
		try (JadxDecompiler decompiler = projectWithResources(400)) {
			AnalysisReport report = new AnalysisReport();
			InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.BEHAVIOR, "Find packaged script", "");
			LocalAnalysisTools tools = new LocalAnalysisTools(decompiler);
			String title = "many-resources.jar!/assets/r399.js";
			String id = "R-" + AiEvidence.fingerprint(title).substring(0, 16);
			assertThat(tools.catalog(run, report)).doesNotContain(title, id);
			String found = tools.execute("search_artifacts", "", "r399.js", run, report, new AiCancellation());
			assertThat(found).contains(id + " | text resource | " + title);
			assertThat(tools.execute("read_resource", id, "", run, report, new AiCancellation())).contains("original-399");
			InvestigationRun restored = new Gson().fromJson(new Gson().toJson(run), InvestigationRun.class);
			LocalAnalysisTools resumed = new LocalAnalysisTools(decompiler);
			assertThat(resumed.catalog(restored, report)).contains(id);
			assertThat(resumed.execute("read_resource", id + "@1", "", restored, report, new AiCancellation())).contains("original-399");
			assertThat(tools.execute("search_artifacts", "", "r3", run, report, new AiCancellation()).lines()
					.filter(line -> line.contains(" | text resource | ")).count()).isEqualTo(20);
			ResourceFile outside = file("not-loaded.js", "must-not-read");
			assertThat(tools.execute("search_artifacts", "", "not-loaded.js", run, report, new AiCancellation()))
					.doesNotContain(" | text resource | ");
			assertThatThrownBy(() -> tools.execute("read_resource", outside.getOriginalName(), "", run, report, new AiCancellation()))
					.isInstanceOf(IllegalArgumentException.class);

			List<FrameworkResourceReader.Entry> registered = FrameworkResourceReader.catalog(decompiler, report);
			for (int i = 128; i < 400; i++) {
				FrameworkResourceReader.search(decompiler, registered, String.format("r%03d.js", i), 20, new AiCancellation());
			}
			assertThat(registered).hasSize(384);
			List<FrameworkResourceReader.Entry> restoredRegistry = FrameworkResourceReader.catalog(decompiler, report);
			FrameworkResourceReader.restore(decompiler, restoredRegistry, registered.stream().map(entry -> entry.title).toList());
			assertThat(restoredRegistry).extracting(entry -> entry.id)
					.containsExactlyElementsOf(registered.stream().map(entry -> entry.id).toList());
			assertThat(FrameworkResourceReader.search(decompiler, registered, "r000.js", 20, new AiCancellation())).hasSize(1);
			assertThat(FrameworkResourceReader.search(decompiler, registered, "r399.js", 20, new AiCancellation())).isEmpty();
		}
	}

	@Test
	void searchOnlySnapshotRestoresResourceIdsWithoutAddingEvidenceOrOwnership() throws Exception {
		try (JadxDecompiler decompiler = projectWithResources(400)) {
			AnalysisReport report = new AnalysisReport();
			InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.BEHAVIOR, "Find script", "");
			LocalAnalysisTools tools = new LocalAnalysisTools(decompiler);
			String title = "many-resources.jar!/assets/r399.js";
			String id = "R-" + AiEvidence.fingerprint(title).substring(0, 16);
			assertThat(tools.execute("search_artifacts", "", "r399.js", run, report, new AiCancellation())).contains(id);
			assertThat(run.getResourceTitles()).hasSize(129).contains(title);
			assertThat(run.getEvidence().entries()).hasSize(1)
					.allSatisfy(entry -> assertThat(entry.getSource().getCode()).doesNotContain("original-399"));
			InvestigationRun restored = new Gson().fromJson(new Gson().toJson(run.snapshot()), InvestigationRun.class);
			LocalAnalysisTools resumed = new LocalAnalysisTools(decompiler);
			assertThat(resumed.execute("read_resource", id, "", restored, report, new AiCancellation())).contains("original-399");
			assertThat(restored.getEvidence().entries()).hasSize(2)
					.allSatisfy(entry -> assertThat(restored.evidenceOwners(entry.getId())).isEmpty());
			assertThat(InvestigationScope.anchors(report, restored)).isEmpty();

			ResourceFile outside = file("not-loaded.js", "must-not-read");
			restored.rememberResources(List.of(outside.getOriginalName()));
			String unknownId = "R-" + AiEvidence.fingerprint(outside.getOriginalName()).substring(0, 16);
			assertThatThrownBy(() -> new LocalAnalysisTools(decompiler)
					.execute("read_resource", unknownId, "", restored.snapshot(), report, new AiCancellation()))
							.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void savedResourceNamesStayBoundedAndOlderRunsHaveAnEmptyRegistry() throws Exception {
		InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.BEHAVIOR, "", "");
		run.rememberResources(java.util.stream.IntStream.range(0, 500).mapToObj(i -> "app.apk!/assets/r" + i + ".js").toList());
		assertThat(run.getResourceTitles()).hasSize(384);
		assertThat(run.snapshot().getResourceTitles()).containsExactlyElementsOf(run.getResourceTitles());
		var saved = new Gson().toJsonTree(run).getAsJsonObject();
		saved.remove("resourceTitles");
		InvestigationRun old = new Gson().fromJson(saved, InvestigationRun.class);
		assertThat(old.getResourceTitles()).isEmpty();
		assertThat(old.snapshot().getResourceTitles()).isEmpty();
	}

	@Test
	void cancellationPreventsReadingAndMaximumResourceOffsetStaysBounded() throws Exception {
		ResourceFile js = file("large.js", "a".repeat(1024 * 1024 + 20));
		var entry = new FrameworkResourceReader.Entry(js.getOriginalName(), js);
		AiCancellation cancelled = new AiCancellation();
		cancelled.cancel();
		assertThatThrownBy(() -> FrameworkResourceReader.read(List.of(entry), entry.id, cancelled))
				.isInstanceOf(InterruptedException.class);
		var last = FrameworkResourceReader.read(List.of(entry), entry.id + "@1048570", new AiCancellation());
		assertThat(last.evidence.getCode()).hasSize(6);
		assertThat(last.evidence.isTruncated()).isTrue();
		assertThat(last.next).isEmpty();
	}

	@Test
	void readsPlaintextBundlesButTreatsHermesAsFormatEvidenceOnly() throws Exception {
		ResourceFile text = file("index.bundle", "fetch('/original-request');");
		var plain = new FrameworkResourceReader.Entry(text.getOriginalName(), text);
		var source = FrameworkResourceReader.read(List.of(plain), plain.id, new AiCancellation());
		assertThat(source.decodedText).isTrue();
		assertThat(source.evidence.getCode()).contains("/original-request");
		Path path = temporary.resolve("hermes.bundle");
		byte[] header = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
				.putLong(0x1F1903C103BC1FC6L).putInt(96).array();
		Files.write(path, header);
		ResourceFile binary = new ResourceFile(null, path.toString(), ResourceType.UNKNOWN) {
		};
		var entry = new FrameworkResourceReader.Entry(path.toString(), binary);
		var observed = FrameworkResourceReader.read(List.of(entry), entry.id, new AiCancellation());
		assertThat(observed.decodedText).isFalse();
		assertThat(observed.evidence.getCode()).contains("Hermes", "96", "No UTF-8 source");
		assertThat(observed.next).isEmpty();
	}

	@Test
	void selectedAndSameSourceObservationsPrecedeUnrelatedHigherRankedSearchHits() throws Exception {
		try (JadxDecompiler decompiler = projectWithResources(40)) {
			AnalysisReport report = new AnalysisReport();
			for (int i = 0; i < 20; i++) {
				report.add(new AnalysisFinding("F" + (i + 3), AnalysisFinding.Kind.HTTP_ROUTE, "/r0/unrelated-" + i,
						new AiEvidence(null, "unrelated-" + i + ".js", "request declaration", "unrelated-" + i, false)));
			}
			AiEvidence selectedSource =
					new AiEvidence(null, "many-resources.jar!/assets/r000.js:1:0", "original-r0-value", "selected", false);
			AnalysisFinding selected =
					new AnalysisFinding("F1", AnalysisFinding.Kind.ENDPOINT, "https://original.example/r0", selectedSource);
			AnalysisFinding related = new AnalysisFinding("F2", AnalysisFinding.Kind.CREDENTIAL, "original-r0-credential",
					new AiEvidence(null, "many-resources.jar!/assets/r000.js:2:60", "source credential", "related", false));
			report.add(selected);
			report.add(related);
			InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.NETWORK, "Find request", "F1");
			run.select(List.of(selected));
			String found = new LocalAnalysisTools(decompiler).execute("search_artifacts", "", "r0", run, report, new AiCancellation());
			List<String> hits = found.lines().filter(line -> line.startsWith("F")).toList();
			assertThat(hits).hasSize(14);
			assertThat(hits.get(0)).startsWith("F1 | ");
			assertThat(hits.get(1)).startsWith("F2 | ");
			assertThat(found.lines().filter(line -> line.contains(" | text resource | ")).count()).isEqualTo(6);
		}
	}

	@Test
	void matchingResourceNamesCannotCrowdOutARequestObservation() throws Exception {
		try (JadxDecompiler decompiler = projectWithResources(40)) {
			AnalysisReport report = new AnalysisReport();
			ApiEndpoint api = new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "POST", "/r0/login", "business-source", null);
			report.addApi(api);
			InvestigationRun run = new InvestigationRun("fixture", "profile", AnalysisGoal.NETWORK, "Find request", "");
			String found = new LocalAnalysisTools(decompiler).execute("search_artifacts", "", "r0", run, report, new AiCancellation());
			assertThat(found).contains(api.getId() + " | ");
			assertThat(found.lines().filter(line -> line.contains(" | text resource | ")).count()).isEqualTo(19);
		}
	}

	private JadxDecompiler projectWithResources(int count) throws Exception {
		Path archive = temporary.resolve("many-resources.jar");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
			for (int i = 0; i < count; i++) {
				output.putNextEntry(new ZipEntry(String.format("assets/r%03d.js", i)));
				output.write(("const token = 'original-" + i + "';").getBytes(StandardCharsets.UTF_8));
				output.closeEntry();
			}
		}
		JadxArgs args = new JadxArgs();
		args.setUseDxInput(false);
		args.setInputFiles(List.of(archive.toFile(), Path.of(AnalysisFixture.class.getResource("AnalysisFixture.class").toURI()).toFile()));
		JadxDecompiler decompiler = new JadxDecompiler(args);
		decompiler.load();
		return decompiler;
	}

	private ResourceFile file(String name, String content) throws Exception {
		Path path = Files.writeString(temporary.resolve(name), content);
		return new ResourceFile(null, path.toString(), ResourceType.UNKNOWN) {
		};
	}
}
