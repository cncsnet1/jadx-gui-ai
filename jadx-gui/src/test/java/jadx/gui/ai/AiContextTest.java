package jadx.gui.ai;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.utils.CodeUtils;
import jadx.gui.ai.fixtures.CallerFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiContextTest {
	@Test
	void collectsRealCallersWithStableReferencesAndBoundedContext() throws Exception {
		Path input = Path.of(CallerFixture.class.getResource("CallerFixture.class").toURI());
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(input.toFile()));
		args.setInlineMethods(false);
		args.setUseDxInput(false);
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			JavaClass cls = decompiler.getClasses().get(0);
			cls.getCode();
			JavaMethod method = cls.getMethods().stream().filter(m -> m.getName().equals("store")).findFirst().orElseThrow();
			String code = CodeUtils.extractMethodCode(method.getMethodNode(), cls.getCodeInfo());
			AiEvidence primary = new AiEvidence(JadxNodeRef.forJavaNode(method), method.getFullName(), code,
					AiEvidence.fingerprint(cls.getCode()), false);
			assertThat(code).contains("saved", "store");
			assertThat(AiContext.collect(decompiler, primary, false, new AiCancellation())).containsExactly(primary);
			List<AiEvidence> evidence = AiContext.collect(decompiler, primary, true, new AiCancellation());
			assertThat(evidence).hasSize(4);
			assertThat(evidence.get(0)).isSameAs(primary);
			for (AiEvidence caller : evidence.subList(1, evidence.size())) {
				assertThat(caller.getCode()).contains("store(");
				assertThat(caller.getCode().length()).isLessThanOrEqualTo(3000);
				assertThat(AiContext.resolve(decompiler, caller.getNode()).getUseIn()).isNotNull();
			}
			AiCancellation cancelled = new AiCancellation();
			cancelled.cancel();
			assertThatThrownBy(() -> AiContext.collect(decompiler, primary, true, cancelled)).isInstanceOf(InterruptedException.class);
		}
	}

	@Test
	void resolvesInnerClassRawNamesAfterRename() throws Exception {
		Path outer = Path.of(CallerFixture.class.getResource("CallerFixture.class").toURI());
		Path inner = Path.of(CallerFixture.Inner.class.getResource("CallerFixture$Inner.class").toURI());
		JadxArgs args = new JadxArgs();
		args.setInputFiles(List.of(outer.toFile(), inner.toFile()));
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			JadxNodeRef ref = new JadxNodeRef(jadx.api.data.IJavaNodeRef.RefType.METHOD, CallerFixture.Inner.class.getName(),
					"nested()Ljava/lang/String;");
			JavaMethod method = (JavaMethod) AiContext.resolve(decompiler, ref);
			assertThat(method).isNotNull();
			method.getMethodNode().rename("renamedNested");
			assertThat(AiContext.resolve(decompiler, ref)).isSameAs(method);
			assertThat(AiContext.resolve(decompiler, JadxNodeRef.forCls("missing.Class"))).isNull();
		}
	}
}
