package jadx.plugins.deobf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class EngineProcess {
	private static final int MAX_LOG_BYTES = 256 * 1024;

	private EngineProcess() {
	}

	static String run(List<String> command, Path directory, int timeout) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		AtomicReference<IOException> readError = new AtomicReference<>();
		Thread reader = new Thread(() -> {
			try (InputStream stream = process.getInputStream()) {
				byte[] buffer = new byte[4096];
				int count;
				while ((count = stream.read(buffer)) != -1) {
					int remaining = MAX_LOG_BYTES - output.size();
					output.write(buffer, 0, Math.min(count, remaining));
					if (count > remaining) {
						readError.compareAndSet(null, new IOException("Engine log exceeded 256 KiB"));
					}
				}
			} catch (IOException e) {
				readError.compareAndSet(null, e);
			}
		}, "java-deobf-output");
		reader.setDaemon(true);
		reader.start();
		try {
			process.getOutputStream().close();
			if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
				throw new IOException("Engine timed out after " + timeout + " seconds");
			}
			reader.join(2000);
			if (reader.isAlive()) {
				throw new IOException("Engine output stream did not close");
			}
			if (readError.get() != null) {
				throw readError.get();
			}
			String log = output.toString(StandardCharsets.UTF_8);
			if (process.exitValue() != 0) {
				throw new IOException("Engine exited with code " + process.exitValue() + "\n" + log);
			}
			return log;
		} finally {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			process.getInputStream().close();
		}
	}
}
