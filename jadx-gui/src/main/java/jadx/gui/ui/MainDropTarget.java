package jadx.gui.ui;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.utils.files.FileUtils;

/**
 * Enables drop support from external applications for the {@link MainWindow} (load dropped APK
 * file)
 */
public class MainDropTarget implements DropTargetListener {

	private static final Logger LOG = LoggerFactory.getLogger(MainDropTarget.class);

	private final Consumer<List<Path>> openFiles;
	private final Consumer<Boolean> feedback;

	public MainDropTarget(MainWindow mainWindow) {
		this(mainWindow::open, mainWindow::setFileDropActive);
	}

	MainDropTarget(Consumer<List<Path>> openFiles) {
		this(openFiles, active -> { });
	}

	MainDropTarget(Consumer<List<Path>> openFiles, Consumer<Boolean> feedback) {
		this.openFiles = openFiles;
		this.feedback = feedback;
	}

	protected void processDrag(DropTargetDragEvent dtde) {
		if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			dtde.acceptDrag(DnDConstants.ACTION_COPY);
			feedback.accept(true);
		} else {
			dtde.rejectDrag();
			feedback.accept(false);
		}
	}

	@Override
	public void dragEnter(DropTargetDragEvent dtde) {
		processDrag(dtde);
	}

	@Override
	public void dragOver(DropTargetDragEvent dtde) {
		processDrag(dtde);
	}

	@Override
	public void dropActionChanged(DropTargetDragEvent dtde) {
		processDrag(dtde);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void drop(DropTargetDropEvent dtde) {
		feedback.accept(false);
		if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			dtde.rejectDrop();
			return;
		}
		dtde.acceptDrop(DnDConstants.ACTION_COPY);
		List<File> transferData;
		try {
			Transferable transferable = dtde.getTransferable();
			transferData = List.copyOf((List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor));
		} catch (Exception e) {
			dtde.dropComplete(false);
			LOG.error("File drop operation failed", e);
			return;
		}
		dtde.dropComplete(!transferData.isEmpty());
		if (!transferData.isEmpty()) {
			// Let the native Windows drag-and-drop callback finish before changing the window.
			SwingUtilities.invokeLater(() -> openFiles.accept(FileUtils.toPaths(transferData)));
		}
	}

	@Override
	public void dragExit(DropTargetEvent dte) {
		feedback.accept(false);
	}
}
