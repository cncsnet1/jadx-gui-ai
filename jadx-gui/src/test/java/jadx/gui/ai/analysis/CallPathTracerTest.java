package jadx.gui.ai.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiEvidence;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallPathTracerTest {
	@TempDir
	Path temp;

	private static JadxNodeRef ref(String name) {
		return new JadxNodeRef(RefType.METHOD, "sample.Flow", name + "()V");
	}

	private static CallPathTracer.Expansion calls(String... names) {
		CallPathTracer.Expansion result = new CallPathTracer.Expansion();
		for (String name : names) {
			result.calls.add(new CallPathTracer.Call(ref(name), 12, "INVOKE_STATIC"));
		}
		if (names.length == 0) {
			result.stops.add(CallPath.Stop.NO_INDEXED_CALLERS);
		}
		return result;
	}

	@Test
	void diamondAndCycleKeepAllEdgesAndExpandEachExactMethodOnce() throws Exception {
		Map<JadxNodeRef, CallPathTracer.Expansion> graph = Map.of(ref("api"), calls("left", "right"),
				ref("left"), calls("entry"), ref("right"), calls("entry"), ref("entry"), calls("api"));
		AtomicInteger reads = new AtomicInteger();
		CallPath path = new CallPathTracer((target, token) -> {
			reads.incrementAndGet();
			return graph.get(target);
		}, 4, 48, 96).trace(ref("api"), new AiCancellation());
		assertThat(reads).hasValue(4);
		assertThat(path.getNodes()).hasSize(4);
		assertThat(path.getEdges()).hasSize(5);
		assertThat(path.node("P4").getDepth()).isEqualTo(2);
		assertThat(path.getEdges()).anyMatch(e -> e.getCaller().equals("P1") && e.getCallee().equals("P4"));
		assertThat(path.hasUnexpanded()).isFalse();
	}

	@Test
	void duplicateCallsDoNotConsumeEdgeBudget() throws Exception {
		CallPath path = new CallPathTracer((target, token) -> target.equals(ref("api")) ? calls("entry", "entry") : calls(),
				4, 48, 1).trace(ref("api"), new AiCancellation());
		assertThat(path.getEdges()).hasSize(1);
		assertThat(path.hasUnexpanded()).isFalse();
	}

	@Test
	void depthNodeAndEdgeLimitsLeaveExplicitFrontiers() throws Exception {
		CallPathTracer.Relations relation = (target, token) -> calls("a", "b", "c");
		CallPath depth = new CallPathTracer(relation, 1, 48, 96).trace(ref("api"), new AiCancellation());
		assertThat(depth.node("P2").getStops()).contains(CallPath.Stop.DEPTH);
		CallPath nodes = new CallPathTracer(relation, 4, 2, 96).trace(ref("api"), new AiCancellation());
		assertThat(nodes.getNodes()).hasSize(2);
		assertThat(nodes.node("P1").getStops()).contains(CallPath.Stop.NODES);
		CallPath edges = new CallPathTracer(relation, 4, 48, 1).trace(ref("api"), new AiCancellation());
		assertThat(edges.getEdges()).hasSize(1);
		assertThat(edges.node("P1").getStops()).contains(CallPath.Stop.EDGES);
		assertThat(edges.hasUnexpanded()).isTrue();
	}

	@Test
	void unresolvedFrontiersKeepObservedEdges() throws Exception {
		CallPathTracer.Expansion partial = calls("entry");
		partial.stops.add(CallPath.Stop.UNRESOLVED);
		CallPath path = new CallPathTracer((target, token) -> target.equals(ref("api")) ? partial : calls(), 4, 48, 96)
				.trace(ref("api"), new AiCancellation());
		assertThat(path.getEdges()).hasSize(1);
		assertThat(path.hasUnexpanded()).isTrue();
		assertThat(path.node("P2").getStops()).contains(CallPath.Stop.NO_INDEXED_CALLERS);
	}

	@Test
	void cancellationPropagatesInsteadOfBecomingNoCallers() {
		AiCancellation token = new AiCancellation();
		CallPathTracer tracer = new CallPathTracer((target, cancellation) -> {
			cancellation.cancel();
			return calls("entry");
		}, 4, 48, 96);
		assertThatThrownBy(() -> tracer.trace(ref("api"), token)).isInstanceOf(InterruptedException.class);
	}

	@Test
	void projectJsonRetainsPathAndRefsCannotBeMutatedThroughGetters() throws Exception {
		CallPath path = new CallPathTracer((target, token) -> calls("entry"), 1, 48, 96).trace(ref("api"), new AiCancellation());
		ApiDossier dossier = new ApiDossier("A1");
		dossier.setCallPath(path);
		var gson = defaultGsonBuilder().create();
		ApiDossier restored = gson.fromJson(gson.toJson(dossier), ApiDossier.class);
		assertThat(restored.getCallPath().describe()).isEqualTo(path.describe());
		restored.getCallPath().node("P2").getMethod().setShortId("wrong()V");
		assertThat(restored.getCallPath().node("P2").getMethod()).isEqualTo(ref("entry"));
		assertThat(restored.describe()).contains("INVOKE_STATIC @ 12", "sample.Flow->entry()V");
	}

	@Test
	void treeHandlesCyclesAndSourceActionUsesSelectedMethod() throws Exception {
		CallPath path = new CallPathTracer((target, token) -> target.equals(ref("api")) ? calls("entry") : calls("api"),
				4, 48, 96).trace(ref("api"), new AiCancellation());
		List<AiEvidence> opened = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
				CallPathPanel panel = new CallPathPanel(opened::add);
				panel.setPath(path);
				var treeField = CallPathPanel.class.getDeclaredField("tree");
				treeField.setAccessible(true);
				JTree tree = (JTree) treeField.get(panel);
				assertThat(tree.getRowCount()).isEqualTo(3);
				var sourceField = CallPathPanel.class.getDeclaredField("source");
				sourceField.setAccessible(true);
				JButton source = (JButton) sourceField.get(panel);
				source.doClick();
				assertThat(opened.get(0).getNode()).isEqualTo(ref("entry"));
				panel.setPath(null);
				assertThat(source.isEnabled()).isFalse();
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	@Test
	void realBytecodeSeparatesOverloadsAndDoesNotInventReflectiveOrLambdaCalls() throws Exception {
		Path source = temp.resolve("Flow.java");
		Files.writeString(source, "public class Flow { "
				+ "public interface Api { String get(int id); String get(String id); } "
				+ "public static String repo(Api a) { return a.get(4); } "
				+ "public static String screen(Api a) { return repo(a); } "
				+ "public static String other(Api a) { return a.get(\"item\"); } "
				+ "public static Object reflect(Api a) throws Exception { return Api.class.getMethod(\"get\", int.class).invoke(a, 4); } "
				+ "public static void helper() {} public static Runnable deferred() { return Flow::helper; } "
				+ "public static void request(java.net.HttpURLConnection c) throws Exception { c.setRequestMethod(\"GET\"); } "
				+ "public static void requestEntry(java.net.HttpURLConnection c) throws Exception { request(c); } "
				+ "public static native void nativeApi(); public static void nativeCaller() { nativeApi(); } }");
		assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", temp.toString(), source.toString())).isZero();
		JadxArgs args = new JadxArgs();
		args.setInlineMethods(false);
		args.setUseDxInput(false);
		try (var files = Files.list(temp)) {
			args.setInputFiles(files.filter(p -> p.toString().endsWith(".class")).map(Path::toFile).toList());
		}
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			JadxNodeRef api = new JadxNodeRef(RefType.METHOD, "Flow$Api", "get(I)Ljava/lang/String;");
			CallPath path = new CallPathTracer(decompiler).trace(api, new AiCancellation());
			assertThat(path.getEdges()).hasSize(2);
			assertThat(path.getNodes()).extracting(n -> n.getMethod().getShortId())
					.containsExactly("get(I)Ljava/lang/String;", "repo(LFlow$Api;)Ljava/lang/String;",
							"screen(LFlow$Api;)Ljava/lang/String;");
			assertThat(path.getEdges()).anyMatch(e -> e.getOpcode().equals("INVOKE_INTERFACE") && e.getOffset() >= 0);
			CallPath overload = new CallPathTracer(decompiler).trace(
					new JadxNodeRef(RefType.METHOD, "Flow$Api", "get(Ljava/lang/String;)Ljava/lang/String;"), new AiCancellation());
			assertThat(overload.getNodes()).extracting(n -> n.getMethod().getShortId()).contains("other(LFlow$Api;)Ljava/lang/String;")
					.doesNotContain("repo(LFlow$Api;)Ljava/lang/String;");
			CallPath lambda =
					new CallPathTracer(decompiler).trace(new JadxNodeRef(RefType.METHOD, "Flow", "helper()V"), new AiCancellation());
			assertThat(lambda.getEdges()).isEmpty();
			CallPath nativePath = new CallPathTracer(decompiler).trace(new JadxNodeRef(RefType.METHOD, "Flow", "nativeApi()V"),
					new AiCancellation());
			assertThat(nativePath.getEdges()).hasSize(1);
			assertThat(nativePath.getNodes()).extracting(n -> n.getMethod().getShortId()).contains("nativeCaller()V");
			AnalysisReport report = new ApkAnalysisScanner(decompiler).scan("fixture", new AiCancellation(), ignored -> {
			});
			ApiEndpoint context = report.getApis().stream().filter(e -> e.getOrigin() == ApiEndpoint.Origin.CODE_CONTEXT)
					.findFirst().orElseThrow();
			ApiDossier dossier = new ApiRelationResolver(decompiler).resolve(context, new AiCancellation());
			assertThat(dossier.getCallPath().getEdges()).hasSize(1);
			assertThat(dossier.getBindings()).isEmpty();
			assertThat(dossier.getGaps()).isNotEmpty();
			ApiEndpoint address = new ApiEndpoint(ApiEndpoint.Origin.ADDRESS, "", "https://example.test/", "constant", api);
			assertThat(new ApiRelationResolver(decompiler).resolve(address, new AiCancellation()).getCallPath()).isNull();
		}
	}
}
