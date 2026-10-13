package jadx.gui.ai.analysis;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;

import jadx.api.data.impl.JadxNodeRef;
import jadx.core.dex.nodes.parser.SignatureParser;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;
import jadx.gui.utils.ui.WorkbenchUi;

/**
 * Reverse call tree with exact source jumps; cross-edges remain visible without recursively
 * duplicating branches.
 */
public final class CallPathPanel extends JPanel {
	private final JLabel summary = WorkbenchUi.accentTitle("", 0);
	private final JTree tree = new JTree(new DefaultMutableTreeNode());
	private final JTextArea details = WorkbenchUi.body("");
	private final JButton source = new JButton(AiText.str("api.path.source"));
	private final JButton evidence = new JButton(AiText.str("api.path.details"));
	private final JPanel body = new JPanel(new CardLayout());
	private final Consumer<AiEvidence> open;
	private CallPath path;

	public CallPathPanel(Consumer<AiEvidence> open) {
		super(new BorderLayout(8, 12));
		this.open = open;
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		JPanel heading = WorkbenchUi.transparent();
		heading.add(summary, BorderLayout.NORTH);
		JTextArea guide = WorkbenchUi.body(AiText.str("api.path.guide"));
		guide.setRows(2);
		heading.add(guide);
		add(heading, BorderLayout.NORTH);
		tree.setRootVisible(true);
		tree.setShowsRootHandles(true);
		tree.setRowHeight(32);
		tree.getAccessibleContext().setAccessibleName(AiText.str("api.path.title"));
		tree.setCellRenderer(new DefaultTreeCellRenderer() {
			@Override
			public Component getTreeCellRendererComponent(JTree view, Object value, boolean selected, boolean expanded,
					boolean leaf, int row, boolean focused) {
				super.getTreeCellRendererComponent(view, value, selected, expanded, leaf, row, focused);
				putClientProperty("html.disable", true);
				setIcon(null);
				Object item = ((DefaultMutableTreeNode) value).getUserObject();
				if (item instanceof Item) {
					Item entry = (Item) item;
					setText(entry.node.getId() + "  " + entry.label + (entry.repeated ? "  ↩" : "")
							+ (entry.node.getStops().isEmpty() ? "" : "  · " + AiText.str("api.path.frontier")));
				}
				return this;
			}
		});
		tree.addTreeSelectionListener(e -> selection());
		details.setFocusable(true);
		details.getAccessibleContext().setAccessibleName(AiText.str("api.path.details"));
		details.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		evidence.addActionListener(e -> {
			JScrollPane scroll = new JScrollPane(details);
			scroll.setPreferredSize(new Dimension(600, 340));
			JOptionPane.showMessageDialog(this, scroll, AiText.str("api.path.details"), JOptionPane.PLAIN_MESSAGE);
		});
		source.addActionListener(e -> {
			Item item = selected();
			if (item != null) {
				JadxNodeRef ref = item.node.getMethod();
				this.open.accept(new AiEvidence(ref, ref.toString(), details.getText(), "", false));
			}
		});
		JTextArea empty = WorkbenchUi.body(AiText.str("api.path.empty_detail"));
		empty.setBorder(BorderFactory.createEmptyBorder(18, 12, 18, 12));
		body.setOpaque(false);
		body.add(new JScrollPane(empty), "empty");
		body.add(new JScrollPane(tree), "tree");
		add(body);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
		actions.setOpaque(false);
		actions.add(evidence);
		actions.add(source);
		add(actions, BorderLayout.SOUTH);
		setPath(null);
	}

	public void setPath(CallPath path) {
		this.path = path;
		if (path == null || path.getNodes().isEmpty()) {
			((CardLayout) body.getLayout()).show(body, "empty");
			summary.setText(AiText.str("api.path.title"));
			summary.setToolTipText(null);
			tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode(AiText.str("api.path.empty"))));
			details.setText(AiText.str("api.path.empty_detail"));
			source.setEnabled(false);
			evidence.setEnabled(false);
			return;
		}
		((CardLayout) body.getLayout()).show(body, "tree");
		summary.setText(path.summary());
		summary.setToolTipText(path.summary());
		DefaultMutableTreeNode root = branch(path.getNodes().get(0), null, new HashSet<>());
		tree.setModel(new DefaultTreeModel(root));
		for (int row = 0; row < tree.getRowCount(); row++) {
			tree.expandRow(row);
		}
		tree.setSelectionRow(tree.getRowCount() > 1 ? 1 : 0);
	}

	private DefaultMutableTreeNode branch(CallPath.Node node, CallPath.Edge edge, Set<String> expanded) {
		boolean repeated = !expanded.add(node.getId());
		DefaultMutableTreeNode branch = new DefaultMutableTreeNode(new Item(node, edge, repeated));
		if (!repeated) {
			for (CallPath.Edge incoming : path.getEdges()) {
				if (incoming.getCallee().equals(node.getId())) {
					branch.add(branch(path.node(incoming.getCaller()), incoming, expanded));
				}
			}
		}
		return branch;
	}

	private Item selected() {
		Object selected = tree.getLastSelectedPathComponent();
		if (selected instanceof DefaultMutableTreeNode) {
			Object item = ((DefaultMutableTreeNode) selected).getUserObject();
			if (item instanceof Item) {
				return (Item) item;
			}
		}
		return null;
	}

	private void selection() {
		Item item = selected();
		source.setEnabled(item != null);
		evidence.setEnabled(item != null);
		if (item == null) {
			return;
		}
		StringBuilder text = new StringBuilder(item.label).append("\n\n");
		if (item.edge == null) {
			text.append(AiText.str("api.path.target"));
		} else {
			text.append(AiText.str("api.path.observed")).append('\n')
					.append(label(path.node(item.edge.getCallee()).getMethod()))
					.append('\n').append(item.edge.getOpcode()).append(" @ ").append(item.edge.getOffset()).append('\n');
		}
		if (item.repeated) {
			text.append('\n').append(AiText.str("api.path.repeated")).append('\n');
		}
		for (CallPath.Stop stop : item.node.getStops()) {
			text.append('\n').append(stop.label());
		}
		text.append("\n\n").append(AiText.str("api.path.boundary"));
		text.append("\n\n").append(AiText.str("api.path.signature")).append('\n').append(item.node.getMethod());
		if (item.edge != null) {
			text.append("\n  → ").append(path.node(item.edge.getCallee()).getMethod());
		}
		details.setText(text.toString());
		details.setCaretPosition(0);
	}

	private static String label(JadxNodeRef ref) {
		String owner = ref.getDeclaringClass();
		String method = ref.getShortId();
		int args = method.indexOf('(');
		if (args >= 0) {
			try {
				String parameters = new SignatureParser(method.substring(args)).consumeMethodArgs(255).stream()
						.map(Object::toString).map(type -> type.substring(type.lastIndexOf('.') + 1))
						.collect(java.util.stream.Collectors.joining(", "));
				method = method.substring(0, args) + '(' + parameters + ')';
			} catch (RuntimeException ignored) {
				// Preserve the original signature if the input descriptor is malformed.
			}
		}
		return owner.substring(owner.lastIndexOf('.') + 1) + '.' + method;
	}

	private static final class Item {
		private final CallPath.Node node;
		private final CallPath.Edge edge;
		private final boolean repeated;
		private final String label;

		Item(CallPath.Node node, CallPath.Edge edge, boolean repeated) {
			this.node = node;
			this.edge = edge;
			this.repeated = repeated;
			this.label = label(node.getMethod());
		}
	}
}
