package jadx.plugins.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.plugins.tools.data.JadxInstalledPlugins;
import jadx.plugins.tools.data.JadxPluginMetadata;

import static jadx.core.utils.GsonUtils.buildGson;
import static org.assertj.core.api.Assertions.assertThat;

class JadxPluginsToolsTest {
	@TempDir
	Path temp;
	private Path registry;
	private Path installed;
	private Path dropins;
	private JadxPluginsTools tools;

	@BeforeEach
	void setup() throws Exception {
		registry = temp.resolve("plugins.json");
		installed = Files.createDirectory(temp.resolve("installed"));
		dropins = Files.createDirectory(temp.resolve("dropins"));
		tools = new JadxPluginsTools(registry, installed, dropins);
	}

	@Test
	void missingNullAndBlankJarFieldsDoNotBlockValidOrDropinPlugins() throws Exception {
		Path enabled = Files.createFile(installed.resolve("enabled.jar"));
		Path disabled = Files.createFile(installed.resolve("disabled.jar"));
		Path dropin = Files.createFile(dropins.resolve("extra.jar"));
		String json = "{\"version\":1,\"installed\":["
				+ "{\"pluginId\":\"missing\"},{\"pluginId\":\"null\",\"jar\":null},"
				+ "{\"pluginId\":\"blank\",\"jar\":\"  \"},"
				+ "{\"pluginId\":\"enabled\",\"jar\":\"enabled.jar\"},"
				+ "{\"pluginId\":\"disabled\",\"jar\":\"disabled.jar\",\"disabled\":true}]}";
		Files.writeString(registry, json);
		assertThat(tools.getEnabledPluginJars()).containsExactlyInAnyOrder(enabled, dropin);
		assertThat(tools.getAllPluginJars()).containsExactlyInAnyOrder(enabled, disabled, dropin);
		assertThat(Files.readString(registry)).isEqualTo(json);
	}

	@Test
	void nullListsAndEntriesAreIgnoredWithoutRewritingTheRegistry() throws Exception {
		Path valid = Files.createFile(installed.resolve("valid.jar"));
		String entries = "{\"installed\":[null,{\"pluginId\":\"valid\",\"jar\":\"valid.jar\"}]}";
		Files.writeString(registry, entries);
		assertThat(tools.getEnabledPluginJars()).containsExactly(valid);
		assertThat(tools.getInstalled()).hasSize(1);
		assertThat(Files.readString(registry)).isEqualTo(entries);
		String nullList = "{\"installed\":null}";
		Files.writeString(registry, nullList);
		Path dropin = Files.createFile(dropins.resolve("extra.jar"));
		assertThat(tools.getEnabledPluginJars()).containsExactly(dropin);
		assertThat(Files.readString(registry)).isEqualTo(nullList);
	}

	@Test
	void invalidMissingAndExternalJarPathsAreSkippedAndCannotDeleteOutsideInstalledDirectory() throws Exception {
		Path outside = Files.writeString(temp.resolve("outside.jar"), "keep this file");
		Files.createDirectory(installed.resolve("directory.jar"));
		for (String jar : List.of("../outside.jar", "..\\outside.jar", outside.toString(), "invalid\0.jar", "missing.jar",
				"directory.jar")) {
			writePlugin("broken", jar);
			assertThat(tools.getEnabledPluginJars()).as(jar).isEmpty();
			assertThat(tools.getAllPluginJars()).as(jar).isEmpty();
		}
		for (String jar : List.of("../outside.jar", outside.toString())) {
			writePlugin("broken", jar);
			assertThat(tools.uninstall("broken")).isTrue();
			assertThat(Files.readString(outside)).isEqualTo("keep this file");
		}
	}

	@Test
	void entryWithoutJarCanStillBeDisabledAndRemoved() throws Exception {
		writePlugin("broken", null);
		assertThat(tools.changeDisabledStatus("broken", true)).isTrue();
		assertThat(tools.getInstalled().get(0).isDisabled()).isTrue();
		assertThat(tools.getEnabledPluginJars()).isEmpty();
		assertThat(tools.uninstall("broken")).isTrue();
		assertThat(tools.getInstalled()).isEmpty();
	}

	@Test
	void unreadableRegistryIsBackedUpAndBundledOrDropinLoadingCanContinue() throws Exception {
		String broken = "{\"installed\":[";
		Files.writeString(registry, broken);
		Path dropin = Files.createFile(dropins.resolve("extra.jar"));
		assertThat(tools.getEnabledPluginJars()).containsExactly(dropin);
		assertThat(Files.exists(registry)).isFalse();
		try (java.util.stream.Stream<Path> files = Files.list(temp)) {
			List<Path> backups = files.filter(p -> p.getFileName().toString().endsWith(".bak"))
					.collect(java.util.stream.Collectors.toList());
			assertThat(backups).hasSize(1);
			assertThat(Files.readString(backups.get(0))).isEqualTo(broken);
		}
		assertThat(tools.getEnabledPluginJars()).containsExactly(dropin);
		Path valid = Files.createFile(installed.resolve("valid.jar"));
		writePlugin("valid", "valid.jar");
		assertThat(tools.getEnabledPluginJars()).containsExactlyInAnyOrder(valid, dropin);
	}

	private void writePlugin(String id, String jar) throws Exception {
		JadxPluginMetadata metadata = new JadxPluginMetadata();
		metadata.setPluginId(id);
		metadata.setJar(jar);
		JadxInstalledPlugins data = new JadxInstalledPlugins();
		data.getInstalled().add(metadata);
		Files.writeString(registry, buildGson().toJson(data));
	}
}
