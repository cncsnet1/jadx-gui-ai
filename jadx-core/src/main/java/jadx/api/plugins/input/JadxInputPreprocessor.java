package jadx.api.plugins.input;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Prepare temporary code inputs before the selected input plugins run.
 * Original project paths and resources are retained. Implementations must not modify inputs in
 * place
 * and must keep temporary outputs alive until the decompiler closes.
 */
@FunctionalInterface
public interface JadxInputPreprocessor {

	List<Path> process(List<Path> inputFiles) throws IOException, InterruptedException;
}
