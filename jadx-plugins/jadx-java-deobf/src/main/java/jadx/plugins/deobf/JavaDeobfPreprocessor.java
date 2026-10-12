package jadx.plugins.deobf;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.input.JadxInputPreprocessor;
import jadx.core.utils.files.FileUtils;

final class JavaDeobfPreprocessor implements JadxInputPreprocessor, Closeable {
	private static final Logger LOG = LoggerFactory.getLogger(JavaDeobfPreprocessor.class);
	private final JavaDeobfOptions options;
	private final Path tempRoot;
	private final JavaDeobfReport report;
	private final Runner runner;
	private final List<Path> workDirs = new ArrayList<>();
	private String resultHash = "original";

	@FunctionalInterface
	interface Runner {
		String run(List<String> command, Path directory, int timeout) throws IOException, InterruptedException;
	}

	JavaDeobfPreprocessor(JavaDeobfOptions options, Path tempRoot, JavaDeobfReport report) {
		this(options, tempRoot, report, EngineProcess::run);
	}

	JavaDeobfPreprocessor(JavaDeobfOptions options, Path tempRoot, JavaDeobfReport report, Runner runner) {
		this.options = options;
		this.tempRoot = tempRoot;
		this.report = report;
		this.runner = runner;
	}

	@Override
	public List<Path> process(List<Path> inputs) throws InterruptedException {
		report.clear();
		resultHash = "original";
		if (options.getMode() == JavaDeobfOptions.Mode.OFF || options.getEngine().isEmpty()) {
			report.add(
					"Preprocessing inactive. Set java-deobf.engine to a trusted Java Deobfuscator JAR and choose a mode. Original inputs are used.");
			return inputs;
		}
		List<Path> result = new ArrayList<>(inputs.size());
		for (Path input : inputs) {
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedException("Java deobfuscation cancelled");
			}
			String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
			if (!name.endsWith(".jar") && !name.endsWith(".class")) {
				result.add(input);
				report.add("SKIPPED " + input + "\nOnly standalone JAR/CLASS inputs are supported.");
				continue;
			}
			try {
				result.add(processOne(input, name.endsWith(".class")));
			} catch (IOException | RuntimeException e) {
				result.add(input);
				String message = String.valueOf(e.getMessage());
				String reason = message.split("\\R", 2)[0];
				report.add("FALLBACK " + input + "\n" + reason + "\nOriginal input retained.");
				report.addDetails("FAILURE " + input + "\n" + message);
				LOG.warn("Java deobfuscation failed for {}; using original input: {}", input, e.getMessage());
			}
		}
		return result;
	}

	private Path processOne(Path input, boolean singleClass) throws IOException, InterruptedException {
		Path engine = Path.of(options.getEngine()).toAbsolutePath();
		if (!Files.isRegularFile(engine)) {
			throw new IOException("Engine JAR does not exist: " + engine);
		}
		Files.createDirectories(tempRoot);
		Path work = Files.createTempDirectory(tempRoot, "run-").toAbsolutePath();
		workDirs.add(work);
		Path source = work.resolve("input.jar");
		if (singleClass) {
			JavaArchive.wrapClass(input, source);
		} else {
			Files.copy(input, source);
		}
		Map<String, String> before = JavaArchive.inspect(source);
		Path outputDir = Files.createDirectory(work.resolve("output"));
		Path output = outputDir.resolve(singleClass ? "deobfuscated.jar" : input.getFileName().toString());
		List<String> rules;
		if (options.getMode() == JavaDeobfOptions.Mode.AUTO || options.getMode() == JavaDeobfOptions.Mode.DETECT) {
			String log = runEngine(engine, work, source, output, true, Collections.emptyList());
			report.addDetails("DETECTION " + input + "\n" + log);
			if (options.getMode() == JavaDeobfOptions.Mode.DETECT) {
				report.add("DETECT ONLY " + input + "\nOriginal input retained.");
				return input;
			}
			rules = TransformerPlan.detect(log);
			if (rules.isEmpty()) {
				report.add("NO SUPPORTED RULE " + input + "\nNo transformation applied; this does not prove the input is unobfuscated.");
				return input;
			}
		} else {
			rules = TransformerPlan.preset(options);
		}
		report.add("PLAN " + input + "\n" + String.join(" -> ", rules));
		LOG.info("Java deobfuscation: {} using {}", input, rules);
		String log = runEngine(engine, work, source, output, false, rules);
		report.addDetails("ENGINE " + input + "\n" + log);
		Map<String, String> after = JavaArchive.inspect(output);
		if (!after.keySet().containsAll(before.keySet())) {
			throw new IOException("Engine removed or renamed input classes; rejecting the incomplete result");
		}
		if (before.equals(after)) {
			report.add("UNCHANGED " + input + "\nEngine finished but no class bytes changed. Original input retained.");
			return input;
		}
		resultHash += JavaArchive.digest(new TreeMap<>(after).toString().getBytes(StandardCharsets.UTF_8));
		report.add("APPLIED " + input + "\nTemporary classes passed structural and class-coverage checks."
				+ " This does not verify semantic equivalence or complete deobfuscation. Project paths and resources remain original.");
		return output;
	}

	private String runEngine(Path engine, Path work, Path input, Path output, boolean detect, List<String> rules)
			throws IOException, InterruptedException {
		Path config = work.resolve(detect ? "detect.yml" : "transform.yml");
		Files.writeString(config, configuration(input, output, detect, rules), StandardCharsets.UTF_8);
		String java = options.getJava();
		if (java.isEmpty()) {
			String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
			java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
		}
		List<String> command = List.of(Path.of(java).toAbsolutePath().toString(), "-Xmx" + options.getMemory() + "m",
				"-Dfile.encoding=UTF-8", "-jar", engine.toString(), "--config", config.toString());
		return runner.run(command, work, options.getTimeout());
	}

	String configuration(Path input, Path output, boolean detect, List<String> rules) {
		StringBuilder config = new StringBuilder();
		config.append("input: ").append(quote(input.toString())).append('\n');
		config.append("output: ").append(quote(output.toString())).append('\n');
		config.append("detect: ").append(detect).append('\n');
		if (!options.getClasspath().isEmpty()) {
			config.append("path:\n");
			for (String item : options.getClasspath().split(Pattern.quote(File.pathSeparator))) {
				if (!item.trim().isEmpty()) {
					Path dependency = Path.of(item.trim()).toAbsolutePath();
					if (!Files.exists(dependency)) {
						throw new IllegalArgumentException("Dependency does not exist: " + dependency);
					}
					config.append("  - ").append(quote(dependency.toString())).append('\n');
				}
			}
		}
		if (!detect) {
			config.append("transformers:\n");
			for (String rule : rules) {
				config.append("  - ").append(quote(rule)).append('\n');
			}
		}
		return config.toString();
	}

	private static String quote(String value) {
		if (value.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Control characters are not allowed in engine configuration");
		}
		return "'" + value.replace("'", "''") + "'";
	}

	String getResultHash() {
		return resultHash;
	}

	@Override
	public void close() {
		for (Path work : workDirs) {
			FileUtils.deleteDirIfExists(work);
		}
		workDirs.clear();
	}
}
