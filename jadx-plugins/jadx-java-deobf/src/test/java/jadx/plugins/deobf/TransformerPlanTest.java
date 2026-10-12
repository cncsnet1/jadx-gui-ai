package jadx.plugins.deobf;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformerPlanTest {
	private static final String END = "\n[main] INFO All detectors have been run.\n";

	@Test
	void choosesOnlyFirstAlternativeAndOrdersZkmDependencies() {
		String log = recommendation("zelix.ReflectionObfuscationTransformer")
				+ recommendation("zelix.StringEncryptionTransformer", "zelix.string.SimpleStringEncryptionTransformer")
				+ recommendation("zelix.StringEncryptionTransformer", "zelix.string.EnhancedStringEncryptionTransformer") + END;
		assertThat(TransformerPlan.detect(log)).containsExactly(
				"zelix.StringEncryptionTransformer", "zelix.ReflectionObfuscationTransformer");
	}

	@Test
	void unknownAndMixedFamiliesRequireManualPlan() {
		assertThatThrownBy(() -> TransformerPlan.detect(recommendation("other.UnknownTransformer") + END))
				.hasMessageContaining("not supported");
		assertThatThrownBy(() -> TransformerPlan.detect(recommendation("zelix.StringEncryptionTransformer")
				+ recommendation("allatori.StringEncryptionTransformer") + END)).hasMessageContaining("Multiple");
		assertThatThrownBy(() -> TransformerPlan.detect(recommendation("zelix.ReflectionObfuscationTransformer") + END))
				.hasMessageContaining("without a supported string rule");
	}

	@Test
	void requiresCompletedDetectionAndIgnoresIncidentalClassNames() {
		assertThatThrownBy(() -> TransformerPlan.detect(recommendation("allatori.StringEncryptionTransformer")))
				.hasMessageContaining("did not complete");
		assertThat(TransformerPlan.detect("com.javadeobfuscator.deobfuscator.transformers.allatori.StringEncryptionTransformer" + END))
				.isEmpty();
		assertThat(TransformerPlan.detect("Recommend transformers:\n\tNone" + END)).isEmpty();
	}

	@Test
	void customPlanKeepsExplicitOrderAndRejectsYaml() {
		JavaDeobfOptions options = new JavaDeobfOptions();
		options.setOptions(Map.of("java-deobf.mode", "custom", "java-deobf.transformers",
				"zelix.FlowObfuscationTransformer, com.javadeobfuscator.deobfuscator.transformers.zelix.StringEncryptionTransformer"));
		assertThat(TransformerPlan.preset(options)).containsExactly("zelix.FlowObfuscationTransformer",
				"zelix.StringEncryptionTransformer");
		options.setOptions(Map.of("java-deobf.mode", "custom", "java-deobf.transformers", "a.B\noutput: other.jar"));
		assertThatThrownBy(() -> TransformerPlan.preset(options)).hasMessageContaining("Invalid transformer");
	}

	static String recommendation(String... names) {
		StringBuilder text = new StringBuilder("\n[main] INFO Recommend transformers:\n"
				+ "[main] INFO (Choose one transformer. If there are multiple, it's recommended to try the transformer listed first)\n");
		for (String name : names) {
			text.append("[main] INFO \tcom.javadeobfuscator.deobfuscator.transformers.").append(name).append('\n');
		}
		return text.toString();
	}
}
