package jadx.gui.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.TooManyListenersException;
import java.util.function.Consumer;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.utils.files.FileUtils;

/** Lets external files reach the window even over controls with their own Swing drop handler. */
final class FileDropSupport extends ContainerAdapter {

	private static final Logger LOG = LoggerFactory.getLogger(FileDropSupport.class);
	private static final String INSTALLED = FileDropSupport.class.getName();
	private static final String FEEDBACK_TARGET = INSTALLED + ".feedback";
	private final Consumer<List<Path>> openFiles;
	private final Consumer<Boolean> feedback;

	FileDropSupport(Consumer<List<Path>> openFiles) {
		this(openFiles, active -> { });
	}

	FileDropSupport(Consumer<List<Path>> openFiles, Consumer<Boolean> feedback) {
		this.openFiles = openFiles;
		this.feedback = feedback;
	}

	void install(Component component) {
		if (component instanceof JComponent) {
			JComponent target = (JComponent) component;
			if (target.getClientProperty(INSTALLED) != null) {
				return;
			}
			target.putClientProperty(INSTALLED, this);
			wrap(target);
			target.addPropertyChangeListener("transferHandler", event -> wrap(target));
		}
		if (component instanceof Container) {
			Container container = (Container) component;
			container.addContainerListener(this);
			for (Component child : container.getComponents()) {
				install(child);
			}
		}
	}

	@Override
	public void componentAdded(ContainerEvent event) {
		install(event.getChild());
	}

	private void wrap(JComponent component) {
		TransferHandler handler = component.getTransferHandler();
		if (handler != null && !(handler instanceof FileHandler)) {
			component.setTransferHandler(new FileHandler(handler));
		}
		DropTarget target = component.getDropTarget();
		if (handler != null && target != null && target != component.getClientProperty(FEEDBACK_TARGET)) {
			component.putClientProperty(FEEDBACK_TARGET, target);
			try {
				// Swing drop targets allow additional listeners for visual feedback.
				target.addDropTargetListener(new DropTargetAdapter() {
					@Override
					public void dragExit(DropTargetEvent event) {
						feedback.accept(false);
					}

					@Override
					public void drop(DropTargetDropEvent event) {
						feedback.accept(false);
					}
				});
			} catch (TooManyListenersException e) {
				LOG.debug("Keeping custom drop target for {}", component.getClass().getSimpleName());
			}
		}
	}

	private final class FileHandler extends TransferHandler {
		private final TransferHandler delegate;

		private FileHandler(TransferHandler delegate) {
			this.delegate = delegate;
		}

		private boolean isFileDrop(TransferSupport support) {
			return support.isDrop() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
		}

		@Override
		public boolean canImport(TransferSupport support) {
			if (isFileDrop(support)) {
				if ((support.getSourceDropActions() & COPY) == 0) {
					feedback.accept(false);
					return false;
				}
				support.setDropAction(COPY);
				support.setShowDropLocation(false);
				feedback.accept(true);
				return true;
			}
			if (support.isDrop()) {
				feedback.accept(false);
			}
			return delegate.canImport(support);
		}

		@Override
		@SuppressWarnings("unchecked")
		public boolean importData(TransferSupport support) {
			if (!isFileDrop(support)) {
				return delegate.importData(support);
			}
			if (!canImport(support)) {
				return false;
			}
			try {
				List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
				if (files.isEmpty()) {
					return false;
				}
				List<Path> paths = FileUtils.toPaths(files);
				SwingUtilities.invokeLater(() -> openFiles.accept(paths));
				return true;
			} catch (Exception e) {
				LOG.error("File drop operation failed", e);
				return false;
			}
		}

		@Override
		public boolean canImport(JComponent component, DataFlavor[] flavors) {
			return delegate.canImport(component, flavors);
		}

		@Override
		public boolean importData(JComponent component, Transferable transferable) {
			return delegate.importData(component, transferable);
		}

		@Override
		public int getSourceActions(JComponent component) {
			return delegate.getSourceActions(component);
		}

		@Override
		public Icon getVisualRepresentation(Transferable transferable) {
			return delegate.getVisualRepresentation(transferable);
		}

		@Override
		public void exportAsDrag(JComponent component, InputEvent event, int action) {
			delegate.exportAsDrag(component, event, action);
		}

		@Override
		public void exportToClipboard(JComponent component, Clipboard clipboard, int action) {
			delegate.exportToClipboard(component, clipboard, action);
		}
	}
}
