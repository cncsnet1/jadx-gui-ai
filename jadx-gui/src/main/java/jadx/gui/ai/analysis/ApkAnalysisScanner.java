package jadx.gui.ai.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.ResourcesLoader;
import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.insns.InsnIndexType;
import jadx.api.plugins.input.insns.Opcode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.xmlgen.ResContainer;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiText;

/** Reads independent bytecode readers, avoiding full-project decompilation and Swing work. */
public final class ApkAnalysisScanner {
	private static final int RESOURCE_LIMIT = 1024 * 1024;
	private final JadxDecompiler decompiler;

	public ApkAnalysisScanner(JadxDecompiler decompiler) {
		this.decompiler = decompiler;
	}

	public static String fingerprint(List<Path> paths, AiCancellation token) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] buffer = new byte[64 * 1024];
		for (Path path : paths) {
			token.check();
			if (!Files.isRegularFile(path)) {
				return "";
			} // Directory projects are never reused without a scan.
			digest.update(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
			try (var input = Files.newInputStream(path)) {
				int read;
				while ((read = input.read(buffer)) != -1) {
					token.check();
					digest.update(buffer, 0, read);
				}
			}
		}
		StringBuilder value = new StringBuilder();
		for (byte b : digest.digest()) {
			value.append(String.format("%02x", b & 0xff));
		}
		return value.toString();
	}

	public AnalysisReport scan(String fingerprint, AiCancellation token, Consumer<AnalysisReport> progress) throws InterruptedException {
		AnalysisReport report = new AnalysisReport();
		report.setAppPackage(decompiler.getRoot().getAppPackage());
		report.setFingerprint(fingerprint);
		report.setEngineProfile(InvestigationService.profile(decompiler));
		AnalysisRules rules = new AnalysisRules(report);
		List<ClassNode> classes = new ArrayList<>(decompiler.getRoot().getClasses());
		List<ResourceFile> resources = new ArrayList<>(decompiler.getResources());
		resources.sort(Comparator.comparing((ResourceFile r) -> r.getType() != ResourceType.MANIFEST)
				.thenComparingInt(FrameworkAnalyzer::priority));
		report.totals(classes.size(), resources.size());
		List<Path> inputs = decompiler.getArgs().getInputFiles().stream().map(java.io.File::toPath)
				.collect(java.util.stream.Collectors.toList());
		report.setProfile(ApkProfileScanner.scan(inputs,
				classes.stream().map(cls -> cls.getClassInfo().getRawName()).collect(java.util.stream.Collectors.toList()), token));
		progress.accept(report.snapshot());
		report.setFrameworkAnalysis(FrameworkAnalyzer.analyze(report.getProfile(), inputs, resources, token, report::addApi));
		progress.accept(report.snapshot());
		long lastPublish = System.nanoTime();
		for (ResourceFile resource : resources) {
			token.check();
			try {
				if (supported(resource)) {
					if (resource.getZipEntry() != null && resource.getZipEntry().getUncompressedSize() > 8L * RESOURCE_LIMIT
							&& !JsApiExtractor.isSupportedResource(resource.getDeobfName())) {
						report.gap(AiText.str("analysis.resource_large") + resource.getDeobfName());
					} else {
						String source = FrameworkAnalyzer.sourceName(resource, inputs);
						scanResource(resource.loadContent(), rules, report, token, source,
								!report.getFrameworkAnalysis().getPrioritizedResources().contains(source)
										&& !FrameworkAnalyzer.excludedRuntime(resource));
						report.resourceRead();
					}
				}
			} catch (Exception e) {
				token.check();
				report.gap(AiText.str("analysis.read_failed") + resource.getDeobfName());
			}
			if (System.nanoTime() - lastPublish > 250_000_000L) {
				progress.accept(report.snapshot());
				lastPublish = System.nanoTime();
			}
		}
		progress.accept(report.snapshot());
		rules.indexResourceReferences();
		classes.sort(
				Comparator.comparingInt((ClassNode cls) -> EvidenceTriage.classRank(report, cls.getClassInfo().getRawName())).reversed());
		for (ClassNode cls : classes) {
			token.check();
			String className = cls.getClassInfo().getRawName();
			for (FieldNode field : cls.getFields()) {
				EncodedValue value = field.get(JadxAttrType.CONSTANT_VALUE);
				if (value != null && value.getValue() instanceof String) {
					String text = field.getName() + " = \"" + value.getValue() + "\"";
					rules.scan(text, className + '.' + field.getName(),
							new JadxNodeRef(RefType.FIELD, className, field.getFieldInfo().getShortId()));
				}
			}
			for (MethodNode method : cls.getMethods()) {
				token.check();
				try {
					scanMethod(method, rules, report, token);
				} catch (Exception e) {
					token.check();
					report.gap(AiText.str("analysis.read_failed") + method.getMethodInfo().getFullId());
				}
			}
			report.classRead();
			if (System.nanoTime() - lastPublish > 250_000_000L) {
				progress.accept(report.snapshot());
				lastPublish = System.nanoTime();
			}
		}
		token.check();
		FrameworkAnalyzer.augmentNative(report);
		report.setComplete(true);
		progress.accept(report.snapshot());
		return report;
	}

	private static void scanMethod(MethodNode method, AnalysisRules rules, AnalysisReport report, AiCancellation token) {
		String className = method.getParentClass().getClassInfo().getRawName();
		JadxNodeRef ref = new JadxNodeRef(RefType.METHOD, className, method.getMethodInfo().getShortId());
		String title = ref.toString();
		report.addApi(ApiExtractor.method(method, title, ref));
		ICodeReader reader = method.getCodeReader();
		if (reader == null) {
			return;
		}
		if (reader.getUnitsCount() > 200_000) {
			report.gap(AiText.str("analysis.method_large") + title);
			return;
		}
		StringBuilder calls = new StringBuilder();
		StringBuilder literals = new StringBuilder();
		reader.copy().visitInstructions(insn -> {
			if (token.isCancelled() || Thread.currentThread().isInterrupted()) {
				throw new CancellationException();
			}
			insn.decode(); // JVM ldc determines its constant type during decoding.
			if (insn.getIndexType() == InsnIndexType.STRING_REF || insn.getOpcode() == Opcode.CONST_STRING) {
				String value = insn.getIndexAsString();
				if (value == null) {
					return;
				}
				rules.linkResource(value, title, ref);
				if (value.length() > 8192) {
					report.gap(AiText.str("analysis.literal_large"));
					value = value.substring(0, 8192);
				}
				String text = "const @" + insn.getOffset() + " = \"" + value + "\"";
				rules.scan(text, title + " @" + insn.getOffset(), ref);
				if (literals.length() < 8000) {
					literals.append(text).append('\n');
				}
			} else if (insn.getIndexType() == InsnIndexType.METHOD_REF && calls.length() < 8000) {
				IMethodRef called = insn.getIndexAsMethod();
				if (called != null) {
					called.load();
					calls.append(called.getParentClassType()).append('.').append(called.getName()).append('\n');
				}
			}
		});
		rules.behavior(calls.toString(), literals.toString(), title, ref);
		FrameworkAnalyzer.observeNativeMethod(report, ref, calls.toString(), literals.toString());
		report.addApi(ApiExtractor.networkContext(calls.toString(), title, ref));
	}

	private static boolean supported(ResourceFile resource) {
		ResourceType type = resource.getType();
		String name = resource.getDeobfName().toLowerCase(Locale.ROOT);
		return type == ResourceType.MANIFEST || type == ResourceType.XML || type == ResourceType.ARSC
				|| name.matches(".*\\.(json|properties|txt|pem|key|yaml|yml|conf|ini|js|bundle|html|htm)$");
	}

	private static void scanResource(ResContainer content, AnalysisRules rules, AnalysisReport report, AiCancellation token,
			String source, boolean extractJs)
			throws Exception {
		token.check();
		if (content == null) {
			throw new IOException("No decoded resource");
		}
		String text;
		boolean[] truncated = { false };
		boolean javaScript = JsApiExtractor.isSupportedResource(content.getName());
		switch (content.getDataType()) {
			case TEXT:
			case RES_TABLE:
				text = content.getText().getCodeStr();
				break;
			case RES_LINK:
				text = ResourcesLoader.decodeStream(content.getResLink(), (size, input) -> {
					if (size > RESOURCE_LIMIT && !javaScript) {
						throw new IOException("Resource limit");
					}
					byte[] bytes = input.readNBytes(RESOURCE_LIMIT + 1);
					if (javaScript && !ScriptContent.isText(bytes)) {
						report.gap((ScriptContent.isHermes(bytes)
								? "Hermes bytecode; text parsing skipped: "
								: "Binary script; text parsing skipped: ")
								+ source);
						return "";
					}
					if (bytes.length > RESOURCE_LIMIT && !javaScript) {
						throw new IOException("Resource limit");
					}
					truncated[0] = bytes.length > RESOURCE_LIMIT || size > RESOURCE_LIMIT;
					return new String(bytes, 0, Math.min(bytes.length, RESOURCE_LIMIT), StandardCharsets.UTF_8);
				});
				break;
			default:
				return;
		}
		if (text.length() > RESOURCE_LIMIT) {
			truncated[0] = true;
			text = text.substring(0, RESOURCE_LIMIT);
		}
		if (truncated[0]) {
			report.gap(AiText.str("analysis.resource_large") + content.getName());
		}
		rules.scan(text, source, null);
		token.check();
		if (javaScript && extractJs) {
			JsApiExtractor.scan(text, source, truncated[0]).forEach(report::addApi);
			token.check();
		}
		for (ResContainer child : content.getSubFiles()) {
			scanResource(child, rules, report, token, child.getName(), true);
		}
	}
}
