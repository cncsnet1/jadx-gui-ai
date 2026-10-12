package jadx.gui.ui;

import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class MainDropTargetTest {

	@BeforeAll
	static void requiresDesktop() {
		assumeFalse(GraphicsEnvironment.isHeadless());
	}

	@Test
	void completesNativeDropBeforeOpeningOnTheEventQueue() throws Exception {
		List<Path> opened = new ArrayList<>();
		File file = new File("app.apk");
		TestDropEvent event = new TestDropEvent(List.of(file));
		MainDropTarget listener = new MainDropTarget(paths -> {
			assertThat(SwingUtilities.isEventDispatchThread()).isTrue();
			assertThat(event.completed).isTrue();
			opened.addAll(paths);
		});
		SwingUtilities.invokeAndWait(() -> {
			listener.drop(event);
			assertThat(event.acceptedAction).isEqualTo(DnDConstants.ACTION_COPY);
			assertThat(event.completed).isTrue();
			assertThat(opened).isEmpty();
		});
		SwingUtilities.invokeAndWait(() -> assertThat(opened).containsExactly(file.toPath()));
	}

	@Test
	void emptyAndFailedTransfersCompleteWithoutOpening() throws Exception {
		List<Path> opened = new ArrayList<>();
		MainDropTarget listener = new MainDropTarget(opened::addAll);
		TestDropEvent empty = new TestDropEvent(List.of());
		TestDropEvent broken = new TestDropEvent(null);
		SwingUtilities.invokeAndWait(() -> {
			listener.drop(empty);
			listener.drop(broken);
			assertThat(empty.completed).isFalse();
			assertThat(broken.completed).isFalse();
		});
		SwingUtilities.invokeAndWait(() -> assertThat(opened).isEmpty());
	}

	private static final class TestDropEvent extends DropTargetDropEvent {
		private final List<File> files;
		private Boolean completed;
		private int acceptedAction;

		private TestDropEvent(List<File> files) {
			super(new DropTarget().getDropTargetContext(), new Point(), DnDConstants.ACTION_COPY, DnDConstants.ACTION_COPY);
			this.files = files;
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.javaFileListFlavor.equals(flavor);
		}

		@Override
		public Transferable getTransferable() {
			return new Transferable() {
				@Override
				public DataFlavor[] getTransferDataFlavors() {
					return new DataFlavor[] { DataFlavor.javaFileListFlavor };
				}

				@Override
				public boolean isDataFlavorSupported(DataFlavor flavor) {
					return DataFlavor.javaFileListFlavor.equals(flavor);
				}

				@Override
				public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
					if (files == null) {
						throw new IOException("Broken transfer fixture");
					}
					return files;
				}
			};
		}

		@Override
		public void acceptDrop(int action) {
			acceptedAction = action;
		}

		@Override
		public void dropComplete(boolean success) {
			assertThat(completed).isNull();
			completed = success;
		}
	}
}
