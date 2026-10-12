package jadx.plugins.deobf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineProcessTest {
	@TempDir
	Path temp;

	@Test
	void capturesOutputAndNonzeroExit() throws Exception {
		assertThat(EngineProcess.run(command("ok"), temp, 10)).contains("engine output");
		assertThatThrownBy(() -> EngineProcess.run(command("fail"), temp, 10))
				.hasMessageContaining("code 7").hasMessageContaining("engine failure");
	}

	@Test
	void timesOutAndTerminatesTheProcess() throws Exception {
		assertThatThrownBy(() -> EngineProcess.run(command("sleep"), temp, 2)).hasMessageContaining("timed out");
		long pid = Long.parseLong(Files.readString(temp.resolve("pid")));
		ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
		if (handle != null) {
			handle.onExit().get(5, TimeUnit.SECONDS);
			assertThat(handle.isAlive()).isFalse();
		}
	}

	@Test
	void drainsExcessiveLogsWithoutDeadlocking() throws Exception {
		assertThatThrownBy(() -> EngineProcess.run(command("flood"), temp, 10)).hasMessageContaining("log exceeded");
	}

	private List<String> command(String mode) throws Exception {
		String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
		String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
		String classes = Path.of(EngineStub.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		return List.of(java, "-cp", classes, EngineStub.class.getName(), mode);
	}

	public static class EngineStub {
		public static void main(String[] args) throws Exception {
			switch (args[0]) {
				case "fail":
					System.err.println("engine failure");
					System.exit(7);
					break;
				case "sleep":
					Files.writeString(Path.of("pid"), Long.toString(ProcessHandle.current().pid()));
					Thread.sleep(30_000);
					break;
				case "flood":
					for (int i = 0; i < 200_000; i++) {
						System.out.println("large engine output");
					}
					break;
				default:
					System.out.println("engine output");
			}
		}
	}
}
