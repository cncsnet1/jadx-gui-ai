package jadx.plugins.deobf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaDeobfPreprocessorTest {
	@TempDir
	Path temp;

	@Test
	void autoTransformsTemporaryCopyAndCleansUp() throws Exception {
		Path input = jar(temp.resolve("input with ' quote.jar"), "Example", "encrypted");
		byte[] original = Files.readAllBytes(input);
		JavaDeobfReport report = new JavaDeobfReport();
		AtomicInteger calls = new AtomicInteger();
		Path result;
		try (JavaDeobfPreprocessor processor = processor("auto", report, (command, directory, timeout) -> {
			calls.incrementAndGet();
			String config = Files.readString(Path.of(command.get(command.size() - 1)));
			assertThat(command).contains("-jar", "--config", "-Xmx1024m");
			if (config.contains("detect: true")) {
				return TransformerPlanTest.recommendation("allatori.StringEncryptionTransformer",
						"allatori.string.StringEncryptionTransformer")
						+ "All detectors have been run.";
			}
			assertThat(config).contains("input with '' quote.jar").doesNotContain("allatori.string.StringEncryptionTransformer");
			jar(directory.resolve("output").resolve(input.getFileName()), "Example", "plaintext");
			return "done";
		})) {
			result = processor.process(List.of(input)).get(0);
			assertThat(result).isNotEqualTo(input).exists();
			assertThat(processor.getResultHash()).isNotEqualTo("original");
			assertThat(calls).hasValue(2);
			assertThat(report.getText()).contains("APPLIED", "semantic equivalence");
			assertThat(Files.readAllBytes(input)).isEqualTo(original);
		}
		assertThat(result).doesNotExist();
	}

	@Test
	void missingClassesAndCorruptOutputFallBackPerInput() throws Exception {
		Path first = jar(temp.resolve("first.jar"), "First", "a");
		Path second = jar(temp.resolve("second.jar"), "Second", "b");
		JavaDeobfReport report = new JavaDeobfReport();
		AtomicInteger calls = new AtomicInteger();
		try (JavaDeobfPreprocessor processor = processor("allatori", report, (command, directory, timeout) -> {
			if (calls.getAndIncrement() == 0) {
				jar(directory.resolve("output/first.jar"), "DifferentClass", "a");
			} else {
				Files.writeString(directory.resolve("output/second.jar"), "not a zip");
			}
			return "finished";
		})) {
			assertThat(processor.process(List.of(first, second))).containsExactly(first, second);
			assertThat(report.getText()).contains("FALLBACK", "removed or renamed", "first.jar", "second.jar");
		}
	}

	@Test
	void detectOnlyNeverTransformsAndNoEngineDoesNotRun() throws Exception {
		Path input = jar(temp.resolve("sample.jar"), "Sample", "value");
		AtomicInteger calls = new AtomicInteger();
		try (JavaDeobfPreprocessor processor = processor("detect", new JavaDeobfReport(), (command, directory, timeout) -> {
			calls.incrementAndGet();
			return TransformerPlanTest.recommendation("allatori.StringEncryptionTransformer");
		})) {
			assertThat(processor.process(List.of(input))).containsExactly(input);
			assertThat(calls).hasValue(1);
		}
		JavaDeobfOptions options = new JavaDeobfOptions();
		options.setOptions(Map.of());
		try (JavaDeobfPreprocessor processor = new JavaDeobfPreprocessor(options, temp, new JavaDeobfReport(), (c, d, t) -> {
			throw new AssertionError("Engine must not run");
		})) {
			assertThat(processor.process(List.of(input))).containsExactly(input);
		}
	}

	@Test
	void unchangedOutputIsNotReportedAsDeobfuscated() throws Exception {
		Path input = jar(temp.resolve("same.jar"), "Same", "value");
		JavaDeobfReport report = new JavaDeobfReport();
		try (JavaDeobfPreprocessor processor = processor("allatori", report, (command, directory, timeout) -> {
			Files.copy(directory.resolve("input.jar"), directory.resolve("output/same.jar"));
			return "finished";
		})) {
			assertThat(processor.process(List.of(input))).containsExactly(input);
			assertThat(report.getText()).contains("UNCHANGED").doesNotContain("APPLIED");
		}
	}

	@Test
	void singleClassWorksAndAndroidInputsAreSkipped() throws Exception {
		Path input = temp.resolve("Example.class");
		Files.write(input, classBytes("Example", "before"));
		Path apk = temp.resolve("example.apk");
		JavaDeobfReport report = new JavaDeobfReport();
		try (JavaDeobfPreprocessor processor = processor("allatori", report, (command, directory, timeout) -> {
			jar(directory.resolve("output/deobfuscated.jar"), "Example", "after");
			return "finished";
		})) {
			List<Path> result = processor.process(List.of(input, apk));
			assertThat(result.get(0)).isNotEqualTo(input);
			assertThat(result.get(1)).isEqualTo(apk);
			assertThat(report.getText()).contains("SKIPPED", "APPLIED");
		}
	}

	@Test
	void cancellationPropagatesInsteadOfStartingOrdinaryDecompilation() throws Exception {
		Path input = jar(temp.resolve("cancel.jar"), "Cancel", "value");
		try (JavaDeobfPreprocessor processor = processor("allatori", new JavaDeobfReport(), (command, directory, timeout) -> {
			throw new InterruptedException("cancelled");
		})) {
			assertThatThrownBy(() -> processor.process(List.of(input))).isInstanceOf(InterruptedException.class);
		}
	}

	@Test
	void rejectsNonstandardArchivesBeforeLaunchingEngine() throws Exception {
		Path input = temp.resolve("nested.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
			zip.putNextEntry(new ZipEntry("lib/nested.jar"));
			zip.write(new byte[] { 1 });
		}
		JavaDeobfReport report = new JavaDeobfReport();
		try (JavaDeobfPreprocessor processor = processor("auto", report, (c, d, t) -> {
			throw new AssertionError("Must inspect archives first");
		})) {
			assertThat(processor.process(List.of(input))).containsExactly(input);
			assertThat(report.getText()).contains("Nested, multi-release");
		}
	}

	@Test
	void rejectsCorruptedClassHeaderEvenIfTheRestCanBeParsed() throws Exception {
		Path input = temp.resolve("corrupt.class");
		byte[] bytes = classBytes("Corrupt", "value");
		bytes[0] = 0;
		Files.write(input, bytes);
		assertThatThrownBy(() -> JavaArchive.wrapClass(input, temp.resolve("wrapped.jar")))
				.hasMessageContaining("Invalid Java class header");
	}

	private JavaDeobfPreprocessor processor(String mode, JavaDeobfReport report, JavaDeobfPreprocessor.Runner runner) throws IOException {
		Path engine = temp.resolve("trusted engine.jar");
		Files.write(engine, new byte[0]);
		JavaDeobfOptions options = new JavaDeobfOptions();
		options.setOptions(Map.of("java-deobf.mode", mode, "java-deobf.engine", engine.toString()));
		return new JavaDeobfPreprocessor(options, temp.resolve("work"), report, runner);
	}

	static Path jar(Path path, String name, String value) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry(name + ".class"));
			zip.write(classBytes(name, value));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("original-resource.txt"));
			zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return path;
	}

	static byte[] classBytes(String name, String value) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "message", "()Ljava/lang/String;", null, null);
		method.visitCode();
		method.visitLdcInsn(value);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(1, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
