package jadx.gui.ai.analysis;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import jadx.zip.security.JadxZipSecurity;

import static org.assertj.core.api.Assertions.assertThat;

class ArchivePathTest {
	@Test
	void rejectsRootedAndCurrentDirectoryNamesOnEveryHost() {
		JadxZipSecurity security = new JadxZipSecurity();
		for (String name : new String[] { "", ".", "./", "..", "../classes.dex", "a/../../classes.dex",
				"a\\..\\classes.dex", "/classes.dex", "\\classes.dex", "C:/classes.dex", "C:classes.dex",
				"\\\\server\\share\\classes.dex", Path.of(".").toAbsolutePath().normalize().toString() }) {
			assertThat(security.isValidEntryName(name)).as(name).isFalse();
		}
		assertThat(security.isValidEntryName(null)).isFalse();
		assertThat(security.isValidEntryName("assets/中文配置.json")).isTrue();
		assertThat(security.isValidEntryName("classes.dex")).isTrue();
		assertThat(security.isValidEntryName("assets/version..txt")).isTrue();
	}
}
