package jadx.gui.ai.analysis;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.analysis.FrameworkAnalysis.Kind;
import jadx.gui.ai.analysis.FrameworkAnalysis.Status;
import jadx.zip.IZipEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameworkAnalyzerTest {
	private static final Path ARCHIVE = Path.of("fixture", "app.apk");
	private static final String ROOT = "assets/apps/demo/www/";

	@Test
	void automaticallyReadsBusinessResourcesAndFindsAllSevenCategoriesWithActualEvidence() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		List<ResourceFile> resources = List.of(
				resource(ARCHIVE, ROOT + "app-service.js", "function login(payload) { const BASE='https://api.example.test'; "
						+ "uni.request({url:BASE+'/login',method:'POST',data:{account:'alice',password:'raw-password'}}); }\n"
						+ "uni.getStorageSync('authToken');uni.setStorageSync('authToken','raw-auth-token');"
						+ "uni.addInterceptor('request',{invoke(args){args.header={Authorization:'raw-auth-header'};}});"
						+ "const plugin=uni.requireNativePlugin('DeviceModule');plus.android.importClass('demo.Native');", reads),
				resource(ARCHIVE, ROOT + "pages.json", "{\"pages\":[{\"path\":\"pages/home/index\"}],"
						+ "\"subPackages\":[{\"root\":\"account\",\"pages\":[{\"path\":\"login\"}]}]}", reads),
				resource(ARCHIVE, ROOT + "uni-app-view.umd.js", "uni.request({url:'/runtime-sdk'});", reads));
		List<ApiEndpoint> apis = new ArrayList<>();
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(uniProfile(), List.of(ARCHIVE), resources, new AiCancellation(), apis::add);
		assertThat(analysis.getFramework()).isEqualTo("uni-app");
		assertThat(analysis.getResourcesRead()).isEqualTo(2);
		assertThat(reads).hasValue(2);
		assertThat(analysis.getSections()).filteredOn(section -> section.getKind() != Kind.CRYPTO)
				.allSatisfy(section -> assertThat(section.getStatus()).isEqualTo(Status.FOUND));
		assertThat(analysis.getSections()).filteredOn(section -> section.getKind() == Kind.CRYPTO)
				.allSatisfy(section -> assertThat(section.getStatus()).isEqualTo(Status.NOT_COVERED));
		assertThat(findings(analysis, Kind.PAGES)).extracting(FrameworkAnalysis.Finding::getTitle)
				.containsExactly("pages/home/index", "account/login");
		assertThat(findings(analysis, Kind.REQUESTS)).extracting(FrameworkAnalysis.Finding::getTitle)
				.contains("function login", "POST https://api.example.test/login", "Request constant");
		assertThat(apis).hasSize(1);
		assertThat(apis.get(0).getParameters()).extracting(ApiEndpoint.Parameter::getValue).contains("raw-password", "alice");
		assertThat(analysis.getGaps()).contains("runtime_sdk_excluded");
		assertThat(analysis.getPrioritizedResources()).containsExactly("app.apk!/" + ROOT + "pages.json",
				"app.apk!/" + ROOT + "app-service.js");
		assertThat(analysis.getFindings()).allSatisfy(finding -> assertThat(finding.getEvidence()).allSatisfy(evidence -> {
			assertThat(evidence.getTitle()).startsWith("app.apk!/");
			assertThat(evidence.getCode()).isNotEmpty();
			assertThat(evidence.getNode()).isNull();
		}));
		assertThat(analysis.markdown()).contains("raw-auth-token", "raw-auth-header", "raw-password");
	}

	@Test
	void ignoresCommentsStringsAndHtmlOutsideScriptAndDoesNotInventDynamicUrls() {
		FrameworkAnalysis analysis = new FrameworkAnalysis();
		List<ApiEndpoint> apis = new ArrayList<>();
		FrameworkAnalyzer.analyzeSource(analysis, "app.apk!/assets/index.html",
				"<p>uni.getStorageSync('fake-html')</p><!--<script>uni.requireNativePlugin('fake-comment');</script>-->"
						+ "<script>// uni.getStorageSync('comment')\nconst text=\"uni.addInterceptor('string',{})\";"
						+ "uni.request({url:runtime+'/path',data:{id:userId}});</script>",
				false, apis::add);
		assertThat(findings(analysis, Kind.AUTH_STORAGE)).isEmpty();
		assertThat(findings(analysis, Kind.INTERCEPTORS)).isEmpty();
		assertThat(findings(analysis, Kind.NATIVE_BRIDGE)).isEmpty();
		assertThat(apis).hasSize(1);
		assertThat(apis.get(0).getAddress()).isEmpty();
		assertThat(apis.get(0).getParameters().get(0).getValue()).isEmpty();
		assertThat(analysis.getGaps()).contains("dynamic_request_url");
		assertThat(analysis.getSections()).filteredOn(section -> section.getKind() == Kind.AUTH_STORAGE)
				.allSatisfy(section -> assertThat(section.getStatus()).isEqualTo(Status.NO_RESULTS));
	}

	@Test
	void prefixAndResourceBudgetsKeepObservedEvidenceAndBusinessPriority() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		List<ResourceFile> resources = new ArrayList<>();
		for (int i = 0; i < 45; i++) {
			resources.add(resource(ARCHIVE, ROOT + "common/" + i + ".js", "const runtime=1;", reads));
		}
		resources.add(resource(ARCHIVE, ROOT + "app-service.js", "uni.request({url:'/first'});/*" + "x".repeat(1024 * 1024), reads));
		List<ApiEndpoint> apis = new ArrayList<>();
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(uniProfile(), List.of(ARCHIVE), resources, new AiCancellation(), apis::add);
		assertThat(analysis.isComplete()).isFalse();
		assertThat(analysis.getResourcesRead()).isEqualTo(40);
		assertThat(reads).hasValue(40);
		assertThat(analysis.getPrioritizedResources().get(0)).endsWith("app-service.js");
		assertThat(analysis.getGaps()).contains("resource_prefix_only", "resource_budget");
		assertThat(apis).hasSize(1);
		assertThat(apis.get(0).getAddress()).isEqualTo("/first");
		assertThat(apis.get(0).isTruncated()).isTrue();
		assertThat(findings(analysis, Kind.REQUESTS).get(0).getEvidence().get(0).isTruncated()).isTrue();
	}

	@Test
	void noBusinessFilesAndOtherFrameworkRoutesAreExplicitlyNotCovered() throws Exception {
		FrameworkAnalysis missing = FrameworkAnalyzer.analyze(uniProfile(), List.of(), List.of(), new AiCancellation(), api -> {
		});
		assertThat(missing.getGaps()).contains("no_business_resources");
		assertThat(missing.getSections()).allSatisfy(section -> assertThat(section.getStatus()).isEqualTo(Status.NOT_COVERED));
		for (String[] framework : List.of(new String[] { "flutter", "Flutter" }, new String[] { "react_native", "React Native" },
				new String[] { "android", "Android / Java / Kotlin" })) {
			ApkProfile profile = new ApkProfile();
			AiEvidence inventory = new AiEvidence(null, "app.apk!/lib/arm64-v8a/libapp.so", "ELF inventory only", "fingerprint", false);
			profile.fact(ApkProfile.Kind.TECHNOLOGY, "technology_" + framework[0], framework[1], ApkProfile.Confidence.OBSERVED,
					List.of(inventory));
			FrameworkAnalysis limited = FrameworkAnalyzer.analyze(profile, List.of(), List.of(), new AiCancellation(), api -> {
			});
			assertThat(limited.getFramework()).isEqualTo(framework[1]);
			assertThat(limited.getResourcesRead()).isZero();
			assertThat(limited.getRoutes()).hasSize(1);
			assertThat(limited.getRoutes().get(0).getStatus()).isEqualTo(FrameworkAnalysis.RouteStatus.INVENTORY_ONLY);
			assertThat(limited.getFindings()).isEmpty();
			assertThat(limited.getSections()).allSatisfy(section -> assertThat(section.getStatus()).isEqualTo(Status.NOT_COVERED));
			AnalysisReport report = new AnalysisReport();
			report.setProfile(profile);
			report.setFrameworkAnalysis(limited);
			assertThat(report.getProfile().getEvidence()).containsExactly(inventory);
			assertThat(report.hasEvidence()).isTrue();
			assertThat(ProfileLead.candidates(report)).isNotEmpty()
					.allSatisfy(finding -> assertThat(finding.getKind()).isNotEqualTo(AnalysisFinding.Kind.FRAMEWORK_CODE));
		}
		AiCancellation cancelled = new AiCancellation();
		cancelled.cancel();
		assertThatThrownBy(() -> FrameworkAnalyzer.analyze(uniProfile(), List.of(ARCHIVE),
				List.of(resource(ARCHIVE, ROOT + "app-service.js", "fetch('/x')", new AtomicInteger())), cancelled, api -> {
				}))
						.isInstanceOf(InterruptedException.class);
	}

	@Test
	void tokenLimitMarksPartialCoverageEvenWhenSourceFitsInsideByteLimit() throws Exception {
		String text = "uni.getStorageSync('seen');" + "a;".repeat(76_000) + "uni.request({url:'/unread'});";
		assertThat(text.length()).isLessThan(1024 * 1024);
		List<ApiEndpoint> apis = new ArrayList<>();
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(uniProfile(), List.of(ARCHIVE),
				List.of(resource(ARCHIVE, ROOT + "app-service.js", text, new AtomicInteger())), new AiCancellation(), apis::add);
		assertThat(analysis.isComplete()).isFalse();
		assertThat(analysis.getGaps()).contains("resource_prefix_only");
		assertThat(analysis.getSections())
				.allSatisfy(section -> assertThat(section.getLimitations()).contains("partial_resource_coverage"));
		assertThat(findings(analysis, Kind.AUTH_STORAGE)).hasSize(1);
		assertThat(apis).isEmpty();
	}

	@Test
	void sourceIdentitySurvivesDuplicateArchivesAndReportSnapshotsAndJson() throws Exception {
		Path duplicate = Path.of("second", "app.apk");
		ResourceFile first = resource(ARCHIVE, ROOT + "app-service.js", "uni.getStorageSync('one')", new AtomicInteger());
		ResourceFile second = resource(duplicate, ROOT + "app-service.js", "uni.getStorageSync('two')", new AtomicInteger());
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(uniProfile(), List.of(ARCHIVE, duplicate), List.of(second, first),
				new AiCancellation(), api -> {
				});
		assertThat(analysis.getPrioritizedResources()).contains("app.apk [2]!/" + ROOT + "app-service.js",
				"app.apk!/" + ROOT + "app-service.js");
		AnalysisReport report = new AnalysisReport();
		report.setFrameworkAnalysis(analysis);
		assertThat(report.hasEvidence()).isTrue();
		AnalysisReport saved = new Gson().fromJson(new Gson().toJson(report.snapshot()), AnalysisReport.class);
		assertThat(saved.getFrameworkAnalysis().getFindings()).extracting(FrameworkAnalysis.Finding::getId)
				.containsExactlyElementsOf(analysis.getFindings().stream().map(FrameworkAnalysis.Finding::getId).toList());
		FrameworkAnalysis.Finding source = findings(saved.getFrameworkAnalysis(), Kind.AUTH_STORAGE).stream()
				.filter(finding -> finding.getTitle().contains("two")).findFirst().orElseThrow();
		assertThat(ResourceEvidenceResolver.resolve(source.getEvidence().get(0).getTitle(), List.of(ARCHIVE, duplicate),
				List.of(first, second)))
						.isSameAs(second);
	}

	private static List<FrameworkAnalysis.Finding> findings(FrameworkAnalysis analysis, Kind kind) {
		return analysis.getFindings().stream().filter(finding -> finding.getKind() == kind).toList();
	}

	@Test
	void mixedRoutesReadEachResourceOnceAndKeepTheirOwnSourceFindings() throws Exception {
		ApkProfile profile = uniProfile();
		technology(profile, "react_native", "React Native");
		technology(profile, "cordova", "Cordova");
		technology(profile, "webview", "WebView");
		technology(profile, "flutter", "Flutter");
		technology(profile, "unity", "Unity");
		technology(profile, "android", "Android");
		AtomicInteger reads = new AtomicInteger();
		List<ResourceFile> resources = List.of(
				resource(ARCHIVE, ROOT + "app-service.js", "uni.request({url:'/uni'});", reads),
				resource(ARCHIVE, "assets/index.android.bundle",
						"fetch('/rn');AsyncStorage.getItem('auth');NativeModules.Account.sign('x');", reads),
				resource(ARCHIVE, "assets/www/index.html",
						"<script>fetch('/web');localStorage.getItem('token');cordova.exec(ok,fail,'Account','sign',[]);</script>", reads),
				resource(ARCHIVE, "assets/www/config.xml",
						"<widget><content src=\"index.html\"/><feature name=\"Account\"><param name=\"android-package\" value=\"demo.Account\"/></feature></widget>",
						reads),
				resource(ARCHIVE, "assets/flutter_assets/AssetManifest.json", "{\"assets/config.json\":[\"assets/config.json\"]}", reads));
		List<ApiEndpoint> apis = new ArrayList<>();
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(profile, List.of(ARCHIVE), resources, new AiCancellation(), apis::add);
		assertThat(analysis.getRoutes()).extracting(FrameworkAnalysis.Route::getId)
				.containsExactly("uni-app", "react-native", "cordova", "webview", "flutter", "unity", "android");
		assertThat(reads).hasValue(5);
		assertThat(analysis.getResourcesRead()).isEqualTo(5);
		assertThat(apis).extracting(ApiEndpoint::getAddress).containsExactlyInAnyOrder("/uni", "/rn", "/web");
		assertThat(route(analysis, "react-native").getFindings())
				.anyMatch(finding -> finding.getTitle().contains("NativeModules.Account.sign"))
				.anyMatch(finding -> finding.getTitle().contains("AsyncStorage.getItem"));
		assertThat(route(analysis, "cordova").getFindings()).anyMatch(finding -> finding.getTitle().equals("demo.Account"));
		assertThat(route(analysis, "flutter").getGaps()).contains("dart_aot_not_decompiled");
		assertThat(route(analysis, "unity").getStatus()).isEqualTo(FrameworkAnalysis.RouteStatus.INVENTORY_ONLY);
		assertThat(route(analysis, "unity").getFindings()).isEmpty();
		assertThat(analysis.getFindings()).allSatisfy(finding -> assertThat(finding.getRouteId()).isNotEmpty());
		assertThat(route(analysis, "react-native").getFindings()).allSatisfy(finding -> assertThat(finding.getEvidence().get(0).getTitle())
				.contains("index.android.bundle"));
		FrameworkAnalysis restored = new Gson().fromJson(new Gson().toJson(analysis.snapshot()), FrameworkAnalysis.class);
		assertThat(restored.getRoutes()).hasSize(7);
		assertThat(route(restored, "cordova").getFindings()).allSatisfy(finding -> assertThat(finding.getRouteId()).isEqualTo("cordova"));
	}

	@Test
	void hermesHeaderIsRecognizedAndNeverParsedAsJavaScript() throws Exception {
		ApkProfile profile = new ApkProfile();
		technology(profile, "react_native", "React Native");
		byte[] bytes = new byte[128];
		java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(0x1F1903C103BC1FC6L).putInt(96);
		byte[] trap = "fetch('/not-source');AsyncStorage.getItem('fake');".getBytes(StandardCharsets.UTF_8);
		System.arraycopy(trap, 0, bytes, 16, trap.length);
		List<ApiEndpoint> apis = new ArrayList<>();
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(profile, List.of(ARCHIVE),
				List.of(resource(ARCHIVE, "assets/index.android.bundle", bytes, new AtomicInteger())), new AiCancellation(), apis::add);
		assertThat(apis).isEmpty();
		assertThat(analysis.getFindings()).isEmpty();
		assertThat(route(analysis, "react-native").getStatus()).isEqualTo(FrameworkAnalysis.RouteStatus.INVENTORY_ONLY);
		assertThat(route(analysis, "react-native").getGaps()).contains("hermes_bytecode_requires_decoder");
		assertThat(route(analysis, "react-native").getEvidence()).anyMatch(source -> source.getCode().contains("version=96")
				&& source.getCode().contains("not been recovered"));
		java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(~0x1F1903C103BC1FC6L);
		assertThat(ScriptContent.isHermes(bytes)).isTrue();
		assertThat(ScriptContent.hermesDescription(bytes)).contains("delta");
	}

	@Test
	void nativeRouteUsesApplicationObservationsWithoutPromotingSdkCrypto() {
		AnalysisReport report = new AnalysisReport();
		report.setAppPackage("demo.app");
		JadxNodeRef business = new JadxNodeRef(RefType.METHOD, "demo.app.Client", "sign()V");
		JadxNodeRef sdk = new JadxNodeRef(RefType.METHOD, "okhttp3.internal.Client", "sign()V");
		AiEvidence appSource = new AiEvidence(business, "demo.app.Client.sign", "Cipher.getInstance(\"AES\");", "app", false);
		AiEvidence sdkSource = new AiEvidence(sdk, "okhttp3.internal.Client.sign", "Cipher.getInstance(\"AES\");", "sdk", false);
		report.add(new AnalysisFinding("F1", AnalysisFinding.Kind.CRYPTO, "AES", sdkSource));
		report.add(new AnalysisFinding("F2", AnalysisFinding.Kind.CRYPTO, "AES", appSource));
		ApiEndpoint endpoint = new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, "POST", "/signed", business.toString(), business);
		report.addApi(endpoint);
		FrameworkAnalyzer.observeNativeMethod(report, business, "Lio/flutter/plugin/common/MethodChannel;.invokeMethod\n",
				"const = \"demo/sign\"");
		FrameworkAnalyzer.observeNativeMethod(report, sdk, "Landroid/webkit/WebView;.loadUrl\n",
				"const = \"file:///android_asset/www/index.html\"");
		FrameworkAnalyzer.augmentNative(report);
		FrameworkAnalysis analysis = report.getFrameworkAnalysis();
		assertThat(analysis.getRoutes()).extracting(FrameworkAnalysis.Route::getId).contains("android", "flutter")
				.doesNotContain("webview");
		assertThat(route(analysis, "android").getFindings()).hasSize(2)
				.allSatisfy(finding -> assertThat(finding.getEvidence().get(0).getNode())
						.isEqualTo(business));
		assertThat(route(analysis, "flutter").getFindings().get(0).getEvidence().get(0).getCode()).contains("demo/sign", "MethodChannel");
		assertThat(route(analysis, "flutter").getGaps()).contains("cross_language_mapping_unresolved");
	}

	@Test
	void oneApplicationMethodKeepsAllObservedFrameworkRoutes() {
		AnalysisReport report = new AnalysisReport();
		report.setAppPackage("demo.app");
		JadxNodeRef method = new JadxNodeRef(RefType.METHOD, "demo.app.HybridEntry", "open()V");
		String calls = "Landroid/webkit/WebView;.loadUrl\nLio/flutter/plugin/common/MethodChannel;.invokeMethod\n"
				+ "Lcom/facebook/react/bridge/ReactContext;.getJSModule\nLorg/apache/cordova/CordovaWebView;.loadUrl\n";
		FrameworkAnalyzer.observeNativeMethod(report, method, calls, "const = \"demo/channel\"");
		FrameworkAnalysis analysis = report.getFrameworkAnalysis();
		assertThat(analysis.getRoutes()).extracting(FrameworkAnalysis.Route::getId)
				.containsExactly("webview", "flutter", "react-native", "cordova");
		assertThat(analysis.getFindings()).extracting(FrameworkAnalysis.Finding::getId).doesNotHaveDuplicates();
		assertThat(analysis.getRoutes()).allSatisfy(route -> {
			assertThat(route.getStatus()).isEqualTo(FrameworkAnalysis.RouteStatus.PARTIAL);
			assertThat(route.getFindings()).hasSize(1).allSatisfy(finding -> {
				assertThat(finding.getRouteId()).isEqualTo(route.getId());
				assertThat(finding.getEvidence()).hasSize(1).allSatisfy(source -> {
					assertThat(source.getNode()).isEqualTo(method);
					assertThat(source.getCode()).contains(calls, "demo/channel", "not resolved argument flow");
				});
			});
		});
	}

	@Test
	void exportedSdkComponentDoesNotPromoteSdkMethodsToBusinessCode() {
		AnalysisReport report = new AnalysisReport();
		report.setAppPackage("demo.app");
		String sdkClass = "io.flutter.embedding.android.FlutterActivity";
		String manifest = "<activity android:name=\"" + sdkClass + "\" android:exported=\"true\"/>";
		AiEvidence manifestSource = new AiEvidence(null, "app.apk!/AndroidManifest.xml", manifest, "manifest", false);
		report.add(new AnalysisFinding("F1", AnalysisFinding.Kind.EXPORTED, manifest, manifestSource));
		JadxNodeRef sdk = new JadxNodeRef(RefType.METHOD, sdkClass, "open()V");
		AiEvidence sdkSource = new AiEvidence(sdk, sdk.toString(), "Cipher.getInstance(\"AES\");", "sdk", false);
		report.add(new AnalysisFinding("F2", AnalysisFinding.Kind.CRYPTO, "AES", sdkSource));
		FrameworkAnalyzer.observeNativeMethod(report, sdk,
				"Lio/flutter/plugin/common/MethodChannel;.invokeMethod\nLandroid/webkit/WebView;.loadUrl\n", "");
		FrameworkAnalyzer.augmentNative(report);
		assertThat(report.getFrameworkAnalysis().getRoutes()).extracting(FrameworkAnalysis.Route::getId).containsExactly("android");
		assertThat(report.getFrameworkAnalysis().getFindings()).hasSize(1)
				.allSatisfy(finding -> assertThat(finding.getEvidence()).containsExactly(manifestSource));

		String otherClass = "partner.Entry";
		String otherEntry = "<activity android:name=\"" + otherClass + "\" android:exported=\"true\"/>";
		report.add(new AnalysisFinding("F3", AnalysisFinding.Kind.EXPORTED, otherEntry,
				new AiEvidence(null, "app.apk!/AndroidManifest.xml", otherEntry, "other-entry", false)));
		JadxNodeRef other = new JadxNodeRef(RefType.METHOD, otherClass, "open()V");
		FrameworkAnalyzer.observeNativeMethod(report, other, "Landroid/webkit/WebView;.loadUrl\n", "");
		assertThat(route(report.getFrameworkAnalysis(), "webview").getFindings()).hasSize(1)
				.allSatisfy(finding -> assertThat(finding.getEvidence().get(0).getNode()).isEqualTo(other));
	}

	@Test
	void failedResourceReadsConsumeTheSharedByteBudget() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		List<ResourceFile> resources = new ArrayList<>();
		for (int i = 0; i < 45; i++) {
			resources.add(resource(ARCHIVE, ROOT + "pages/" + i + ".js", new byte[0], reads, true));
		}
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(uniProfile(), List.of(ARCHIVE), resources,
				new AiCancellation(), api -> {
				});
		assertThat(reads).hasValue(12);
		assertThat(analysis.getResourcesRead()).isZero();
		assertThat(analysis.isComplete()).isFalse();
		assertThat(analysis.getGaps()).contains("resource_read_failed", "resource_budget");
		assertThat(analysis.getFindings()).isEmpty();
	}

	@Test
	void failedReadCountsTowardResourceLimitWhileSuccessfulEvidenceSurvives() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		List<ResourceFile> resources = new ArrayList<>();
		resources.add(resource(ARCHIVE, ROOT + "pages/00.js", new byte[0], reads, true));
		for (int i = 1; i < 45; i++) {
			resources.add(resource(ARCHIVE, ROOT + "pages/" + i + ".js", "fetch('/seen" + i + "');", reads));
		}
		List<ApiEndpoint> apis = new ArrayList<>();
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(uniProfile(), List.of(ARCHIVE), resources,
				new AiCancellation(), apis::add);
		assertThat(reads).hasValue(40);
		assertThat(analysis.getResourcesRead()).isEqualTo(39);
		assertThat(apis).hasSize(39);
		assertThat(analysis.isComplete()).isFalse();
		assertThat(analysis.getGaps()).contains("resource_read_failed", "resource_budget");
	}

	@Test
	void sharedBudgetGivesMixedRoutesTheirFirstBusinessResourceAndBlocksExternalXmlEntities() throws Exception {
		ApkProfile profile = uniProfile();
		technology(profile, "react_native", "React Native");
		technology(profile, "cordova", "Cordova");
		List<ResourceFile> resources = new ArrayList<>();
		AtomicInteger reads = new AtomicInteger();
		for (int i = 0; i < 45; i++) {
			resources.add(resource(ARCHIVE, ROOT + "pages/" + i + ".js", "uni.request({url:'/uni" + i + "'});", reads));
		}
		resources.add(resource(ARCHIVE, "assets/index.android.bundle", "fetch('/rn-budget');", reads));
		resources.add(resource(ARCHIVE, "assets/www/config.xml",
				"<!DOCTYPE widget [<!ENTITY secret SYSTEM 'file:///unread'>]><widget><content src=\"&secret;\"/></widget>", reads));
		List<ApiEndpoint> apis = new ArrayList<>();
		FrameworkAnalysis analysis = FrameworkAnalyzer.analyze(profile, List.of(ARCHIVE), resources, new AiCancellation(), apis::add);
		assertThat(reads).hasValue(40);
		assertThat(analysis.getGaps()).contains("resource_budget", "configuration_parse_incomplete");
		assertThat(apis).anyMatch(api -> api.getAddress().equals("/rn-budget"));
		assertThat(route(analysis, "cordova").getFindings()).noneMatch(finding -> finding.getKind() == Kind.PAGES);
		assertThat(route(analysis, "react-native").getResourcesRead()).isEqualTo(1);
	}

	@Test
	void androidCryptoRemainsCryptoAndDoesNotBecomeARequestWrapper() {
		AnalysisReport report = new AnalysisReport();
		report.setAppPackage("com.example.app");
		JadxNodeRef ref = new JadxNodeRef(RefType.METHOD, "com.example.app.Crypto", "encrypt()V");
		report.add(new AnalysisFinding("F1", AnalysisFinding.Kind.CRYPTO, "Cipher.getInstance",
				new AiEvidence(ref, ref.toString(), "Ljavax/crypto/Cipher;.getInstance", "fixture", false)));
		FrameworkAnalyzer.augmentNative(report);
		assertThat(route(report.getFrameworkAnalysis(), "android").getFindings()).hasSize(1)
				.allSatisfy(finding -> assertThat(finding.getKind()).isEqualTo(Kind.CRYPTO));
		assertThat(KeyCodePanel.frameworkGroup(Kind.CRYPTO)).isEqualTo("crypto");
		assertThat(route(report.getFrameworkAnalysis(), "android").getSections()).filteredOn(section -> section.getKind() == Kind.REQUESTS)
				.allSatisfy(section -> assertThat(section.getStatus()).isEqualTo(Status.NOT_COVERED));
	}

	private static FrameworkAnalysis.Route route(FrameworkAnalysis analysis, String id) {
		return analysis.getRoutes().stream().filter(route -> route.getId().equals(id)).findFirst().orElseThrow();
	}

	private static void technology(ApkProfile profile, String code, String name) {
		profile.fact(ApkProfile.Kind.TECHNOLOGY, "technology_" + code, name, ApkProfile.Confidence.OBSERVED,
				List.of(new AiEvidence(null, "app.apk!/" + name, "Combined test inventory", "fixture-" + code, false)));
	}

	private static ApkProfile uniProfile() {
		ApkProfile profile = new ApkProfile();
		profile.fact(ApkProfile.Kind.TECHNOLOGY, "technology_uniapp", "uni-app", ApkProfile.Confidence.OBSERVED,
				List.of(new AiEvidence(null, "app.apk!/assets/apps/demo/www/app-service.js", "JavaScript asset", "fixture", false)));
		return profile;
	}

	private static ResourceFile resource(Path archive, String name, String content, AtomicInteger reads) {
		return resource(archive, name, content.getBytes(StandardCharsets.UTF_8), reads);
	}

	private static ResourceFile resource(Path archive, String name, byte[] bytes, AtomicInteger reads) {
		return resource(archive, name, bytes, reads, false);
	}

	private static ResourceFile resource(Path archive, String name, byte[] bytes, AtomicInteger reads, boolean fail) {
		return new ResourceFile(null, name, ResourceType.UNKNOWN) {
			@Override
			public IZipEntry getZipEntry() {
				return new IZipEntry() {
					@Override
					public String getName() {
						return name;
					}

					@Override
					public byte[] getBytes() {
						throw new AssertionError("Resource read must be bounded, not getBytes");
					}

					@Override
					public InputStream getInputStream() {
						reads.incrementAndGet();
						if (fail) {
							return new InputStream() {
								@Override
								public int read() throws IOException {
									throw new IOException("Synthetic resource read failure");
								}
							};
						}
						return new ByteArrayInputStream(bytes);
					}

					@Override
					public long getCompressedSize() {
						return bytes.length;
					}

					@Override
					public long getUncompressedSize() {
						return bytes.length;
					}

					@Override
					public boolean isDirectory() {
						return false;
					}

					@Override
					public File getZipFile() {
						return archive.toFile();
					}

					@Override
					public boolean preferBytes() {
						return false;
					}
				};
			}
		};
	}
}
