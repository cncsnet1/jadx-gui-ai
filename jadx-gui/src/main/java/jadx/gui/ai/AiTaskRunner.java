package jadx.gui.ai;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One bounded worker per workbench. UI callers must also guard callbacks queued on the EDT. */
public final class AiTaskRunner implements AutoCloseable {
	@FunctionalInterface
	public interface Task<T> {
		T run(AiCancellation cancellation) throws Exception;
	}

	private final AtomicLong revision = new AtomicLong();
	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(1), runnable -> {
				Thread thread = new Thread(runnable, "jadx-ai-workbench");
				thread.setDaemon(true);
				return thread;
			});
	private AiCancellation cancellation;
	private Future<?> future;

	public long revision() {
		return revision.get();
	}

	public synchronized void cancel() {
		revision.incrementAndGet();
		if (cancellation != null) {
			cancellation.cancel();
		}
		if (future != null) {
			future.cancel(true);
		}
		executor.purge();
	}

	public synchronized <T> void submit(Task<T> task, Consumer<T> success, Consumer<Exception> failure) {
		cancel();
		long id = revision.get();
		AiCancellation token = new AiCancellation();
		cancellation = token;
		future = executor.submit(() -> {
			try {
				T result = task.run(token);
				if (revision.get() == id && !token.isCancelled()) {
					success.accept(result);
				}
			} catch (Exception e) {
				if (revision.get() == id && !token.isCancelled()) {
					failure.accept(e);
				}
			}
		});
	}

	@Override
	public synchronized void close() {
		cancel();
		executor.shutdownNow();
	}
}
