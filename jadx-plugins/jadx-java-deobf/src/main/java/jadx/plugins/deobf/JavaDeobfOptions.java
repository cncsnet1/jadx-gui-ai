package jadx.plugins.deobf;

import jadx.api.plugins.options.OptionFlag;
import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;

public class JavaDeobfOptions extends BasePluginOptionsBuilder {
	public enum Mode {
		OFF, AUTO, DETECT, ZKM, ALLATORI, STRINGER, CUSTOM
	}

	private Mode mode;
	private String engine;
	private String java;
	private String classpath;
	private String transformers;
	private int timeout;
	private int memory;

	@Override
	public void registerOptions() {
		enumOption("java-deobf.mode", Mode.values(), Mode::valueOf)
				.description("Mode (auto requires an engine JAR)")
				.flags(OptionFlag.PER_PROJECT)
				.defaultValue(Mode.AUTO).setter(v -> mode = v);
		strOption("java-deobf.engine")
				.description("Java Deobfuscator JAR (empty: disabled)")
				.defaultValue("").setter(v -> engine = v.trim());
		strOption("java-deobf.java")
				.description("Java executable (empty: current runtime)")
				.defaultValue("").setter(v -> java = v.trim());
		strOption("java-deobf.classpath")
				.description("Dependency paths (; on Windows, : on Unix)")
				.flags(OptionFlag.PER_PROJECT)
				.defaultValue("").setter(v -> classpath = v.trim());
		strOption("java-deobf.transformers")
				.description("Custom transformers (comma-separated, ordered)")
				.flags(OptionFlag.PER_PROJECT)
				.defaultValue("").setter(v -> transformers = v.trim());
		intOption("java-deobf.timeout")
				.description("Timeout per process, seconds (1-3600)")
				.defaultValue(120).setter(v -> timeout = inRange(v, 1, 3600));
		intOption("java-deobf.memory")
				.description("Engine heap, MiB (128-8192)")
				.defaultValue(1024).setter(v -> memory = inRange(v, 128, 8192));
	}

	private static int inRange(int value, int min, int max) {
		if (value < min || value > max) {
			throw new IllegalArgumentException("Expected a value between " + min + " and " + max);
		}
		return value;
	}

	public Mode getMode() {
		return mode;
	}

	public String getEngine() {
		return engine;
	}

	public String getJava() {
		return java;
	}

	public String getClasspath() {
		return classpath;
	}

	public String getTransformers() {
		return transformers;
	}

	public int getTimeout() {
		return timeout;
	}

	public int getMemory() {
		return memory;
	}
}
