package jadx.gui.ai;

import java.util.ArrayList;
import java.util.List;

/** Cancellation belongs to one request; it is never reset or reused. */
public final class AiCancellation {

	private final List<Runnable> listeners = new ArrayList<>();
	private volatile boolean cancelled;

	public void cancel() {
		List<Runnable> callbacks;
		synchronized (this) {
			if (cancelled) {
				return;
			}
			cancelled = true;
			callbacks = new ArrayList<>(listeners);
			listeners.clear();
		}
		callbacks.forEach(Runnable::run);
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void check() throws InterruptedException {
		if (cancelled || Thread.currentThread().isInterrupted()) {
			throw new InterruptedException("AI request cancelled");
		}
	}

	public void onCancel(Runnable listener) {
		synchronized (this) {
			if (!cancelled) {
				listeners.add(listener);
				return;
			}
		}
		listener.run();
	}

	public synchronized void removeListener(Runnable listener) {
		listeners.remove(listener);
	}
}
