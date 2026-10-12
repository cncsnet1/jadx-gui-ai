package jadx.gui.ui;

import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class FileDropSupportTest {

	@BeforeAll
	static void requiresDesktop() {
		assumeFalse(GraphicsEnvironment.isHeadless());
	}

	@Test
	void droppingAnApkOverReadonlyTextOpensItAfterTheDropReturns() throws Exception {
		List<Path> opened = new ArrayList<>();
		List<Boolean> feedback = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> {
			JTextArea text = new JTextArea("Drop an APK here");
			text.setEditable(false);
			new FileDropSupport(opened::addAll, feedback::add).install(text);
			FileDrop event = new FileDrop(text, List.of(new File("app.apk.1")));
			event.dispatch();
			assertThat(event.completed).isTrue();
			assertThat(feedback).contains(true).endsWith(false);
			assertThat(opened).isEmpty();
		});
		SwingUtilities.invokeAndWait(() -> assertThat(opened).containsExactly(Path.of("app.apk.1")));
	}

	@Test
	void newControlsAndReplacedHandlersContinueToAcceptFiles() throws Exception {
		List<Path> opened = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> {
			JPanel root = new JPanel();
			new FileDropSupport(opened::addAll).install(root);
			JTextArea text = new JTextArea();
			root.add(text);
			text.setTransferHandler(new TransferHandler("text"));
			FileDrop event = new FileDrop(text, List.of(new File("second.apk")));
			event.dispatch();
			assertThat(event.completed).isTrue();
		});
		SwingUtilities.invokeAndWait(() -> assertThat(opened).containsExactly(Path.of("second.apk")));
	}

	@Test
	void keepsTextPasteAndCopyWorking() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			JTextArea text = new JTextArea();
			new FileDropSupport(paths -> {
				throw new AssertionError("Text must not open a project");
			}).install(text);
			TransferHandler handler = text.getTransferHandler();
			TransferHandler.TransferSupport support = new TransferHandler.TransferSupport(text, new StringSelection("sample"));
			assertThat(handler.canImport(support)).isTrue();
			assertThat(handler.importData(support)).isTrue();
			assertThat(text.getText()).isEqualTo("sample");
			text.selectAll();
			Clipboard clipboard = new Clipboard("isolated test clipboard");
			handler.exportToClipboard(text, clipboard, TransferHandler.COPY);
			try {
				assertThat(clipboard.getData(DataFlavor.stringFlavor)).isEqualTo("sample");
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
	}

	@Test
	void emptyFileDropDoesNotOpenAProject() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			JTextArea text = new JTextArea();
			new FileDropSupport(paths -> {
				throw new AssertionError("Empty drop must not open a project");
			}).install(text);
			FileDrop event = new FileDrop(text, List.of());
			event.dispatch();
			assertThat(event.completed).isFalse();
		});
	}

	private static final class FileDrop extends DropTargetDropEvent {
		private final List<File> files;
		private Boolean completed;

		private FileDrop(JTextArea text, List<File> files) {
			super(text.getDropTarget().getDropTargetContext(), new Point(), DnDConstants.ACTION_COPY, DnDConstants.ACTION_COPY);
			this.files = files;
		}

		private void dispatch() {
			var target = getDropTargetContext().getDropTarget();
			target.dragEnter(new DropTargetDragEvent(getDropTargetContext(), new Point(),
					DnDConstants.ACTION_COPY, DnDConstants.ACTION_COPY) {
				@Override
				public DataFlavor[] getCurrentDataFlavors() {
					return FileDrop.this.getCurrentDataFlavors();
				}

				@Override
				public boolean isDataFlavorSupported(DataFlavor flavor) {
					return FileDrop.this.isDataFlavorSupported(flavor);
				}

				@Override
				public Transferable getTransferable() {
					return FileDrop.this.getTransferable();
				}

				@Override
				public void acceptDrag(int action) {
					assertThat(action).isEqualTo(DnDConstants.ACTION_COPY);
				}
			});
			target.drop(this);
		}

		@Override
		public DataFlavor[] getCurrentDataFlavors() {
			return new DataFlavor[] { DataFlavor.javaFileListFlavor };
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
					return getCurrentDataFlavors();
				}

				@Override
				public boolean isDataFlavorSupported(DataFlavor flavor) {
					return DataFlavor.javaFileListFlavor.equals(flavor);
				}

				@Override
				public Object getTransferData(DataFlavor flavor) {
					return files;
				}
			};
		}

		@Override
		public void acceptDrop(int action) {
			assertThat(action).isEqualTo(DnDConstants.ACTION_COPY);
		}

		@Override
		public void rejectDrop() {
			completed = false;
		}

		@Override
		public void dropComplete(boolean success) {
			completed = success;
		}
	}
}
