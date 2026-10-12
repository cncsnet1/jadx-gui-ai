package jadx.plugins.deobf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourcesLoader;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.input.JadxInputPreprocessor;
import jadx.api.plugins.loader.JadxBasePluginLoader;
import jadx.api.usage.impl.InMemoryUsageInfoCache;
import jadx.plugins.input.java.JavaInputPlugin;

import static org.assertj.core.api.Assertions.assertThat;

class InputPreprocessorIntegrationTest {
	@TempDir
	Path temp;

	@Test
	void loadsTransformedCodeButKeepsProjectPathsAndResources() throws Exception {
		Path original = JavaDeobfPreprocessorTest.jar(temp.resolve("original.jar"), "Example", "original");
		Path transformed = JavaDeobfPreprocessorTest.jar(temp.resolve("transformed.jar"), "Example", "transformed");
		JadxArgs args = args(original, inputs -> List.of(transformed));
		InMemoryUsageInfoCache oldCache = new InMemoryUsageInfoCache();
		args.setUsageInfoCache(oldCache);
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();
			assertThat(jadx.getClasses().get(0).getCode()).contains("\"transformed\"");
			assertThat(args.getInputFiles()).containsExactly(original.toFile());
			ResourceFile resource = jadx.getResources().stream().filter(r -> r.getOriginalName().endsWith("original-resource.txt"))
					.findFirst().orElseThrow();
			String content = ResourcesLoader.decodeStream(resource,
					(size, stream) -> new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			assertThat(content).isEqualTo("original");
			assertThat(args.getUsageInfoCache()).isNotSameAs(oldCache);
		}
	}

	@Test
	void failedPreprocessorRetainsOriginalCode() throws Exception {
		Path original = JavaDeobfPreprocessorTest.jar(temp.resolve("original.jar"), "Example", "original");
		try (JadxDecompiler jadx = new JadxDecompiler(args(original, inputs -> {
			throw new IOException("simulated failure");
		}))) {
			jadx.load();
			assertThat(jadx.getClasses().get(0).getCode()).contains("\"original\"");
		}
	}

	private JadxArgs args(Path input, JadxInputPreprocessor preprocessor) {
		JadxArgs args = new JadxArgs();
		args.setInputFile(input.toFile());
		args.setPluginLoader(new JadxBasePluginLoader() {
			@Override
			public List<JadxPlugin> load() {
				return List.of(new JavaInputPlugin(), new JadxPlugin() {
					@Override
					public JadxPluginInfo getPluginInfo() {
						return new JadxPluginInfo("test-preprocessor", "Test", "Test preprocessing before Java input");
					}

					@Override
					public void init(JadxPluginContext context) {
						context.addInputPreprocessor(preprocessor);
					}
				});
			}
		});
		return args;
	}
}
