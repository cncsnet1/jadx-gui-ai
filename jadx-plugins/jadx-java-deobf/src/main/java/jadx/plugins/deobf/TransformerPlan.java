package jadx.plugins.deobf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TransformerPlan {
	private static final String PREFIX = "com.javadeobfuscator.deobfuscator.transformers.";
	private static final Pattern RECOMMENDATION = Pattern.compile(
			"(?:^|\\s)" + Pattern.quote(PREFIX) + "([\\w.]+)\\s*$");
	private static final Set<String> AUTO_RULES = Set.of(
			"zelix.StringEncryptionTransformer", "zelix.ReflectionObfuscationTransformer",
			"allatori.StringEncryptionTransformer", "allatori.string.StringEncryptionTransformer",
			"stringer.StringEncryptionTransformer", "stringer.v3.StringEncryptionTransformer",
			"stringer.v3_1.StringEncryptionTransformer", "stringer.v9.StringEncryptionTransformer",
			"stringer.HideAccessObfuscationTransformer", "stringer.InvokedynamicTransformer",
			"stringer.invokedynamic.Invokedynamic1Transformer", "stringer.invokedynamic.Invokedynamic2Transformer");

	private TransformerPlan() {
	}

	static List<String> detect(String log) {
		if (!log.contains("All detectors have been run.")) {
			throw new IllegalArgumentException("Detection did not complete or the engine uses an unsupported report format");
		}
		Set<String> selected = new LinkedHashSet<>();
		boolean chooseFirst = false;
		for (String line : log.split("\\R")) {
			if (line.contains("Recommend transformers:")) {
				chooseFirst = true;
				continue;
			}
			Matcher matcher = RECOMMENDATION.matcher(line);
			if (chooseFirst && matcher.find()) {
				String name = matcher.group(1);
				if (!AUTO_RULES.contains(name)) {
					throw new IllegalArgumentException("Automatic rule is not supported: " + name + "; use custom mode");
				}
				selected.add(name);
				chooseFirst = false; // Upstream lists alternatives, not a pipeline.
			}
		}
		long families = selected.stream().map(s -> s.substring(0, s.indexOf('.'))).distinct().count();
		if (families > 1) {
			throw new IllegalArgumentException("Multiple obfuscator families detected; specify their order in custom mode");
		}
		List<String> result = new ArrayList<>(selected);
		// ZKM reflection needs decrypted strings, even though its detector runs first.
		if (result.remove("zelix.StringEncryptionTransformer")) {
			result.add(0, "zelix.StringEncryptionTransformer");
		}
		if (result.contains("zelix.ReflectionObfuscationTransformer") && !result.contains("zelix.StringEncryptionTransformer")) {
			throw new IllegalArgumentException("ZKM reflection detected without a supported string rule; use custom mode");
		}
		return result;
	}

	static List<String> preset(JavaDeobfOptions options) {
		switch (options.getMode()) {
			case ZKM:
				return List.of("zelix.StringEncryptionTransformer", "zelix.ReflectionObfuscationTransformer",
						"zelix.FlowObfuscationTransformer");
			case ALLATORI:
				return List.of("allatori.StringEncryptionTransformer", "allatori.FlowObfuscationTransformer");
			case STRINGER:
				return List.of("stringer.StringEncryptionTransformer", "stringer.HideAccessObfuscationTransformer",
						"stringer.InvokedynamicTransformer");
			case CUSTOM:
				List<String> result = new ArrayList<>();
				for (String value : options.getTransformers().split(",", -1)) {
					String name = value.trim();
					if (name.startsWith(PREFIX)) {
						name = name.substring(PREFIX.length());
					}
					if (!name.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+")) {
						throw new IllegalArgumentException("Invalid transformer name: " + name);
					}
					result.add(name);
				}
				return result;
			default:
				throw new IllegalArgumentException("No preset for " + options.getMode());
		}
	}
}
