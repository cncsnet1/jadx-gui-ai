package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiText;

/**
 * A bounded incoming-call graph. Edges are bytecode observations, never runtime reachability
 * claims.
 */
public final class CallPath {

	public enum Stop {
		NO_INDEXED_CALLERS, DEPTH, NODES, EDGES, REFERENCES, METHOD_SIZE, READS, TIME, UNRESOLVED, UNVERIFIED;

		public String label() {
			return AiText.str("api.path.stop." + name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	public static final class Node {
		private String id;
		private JadxNodeRef method;
		private int depth;
		private List<Stop> stops = new ArrayList<>();

		private Node() {
		}

		Node(String id, JadxNodeRef method, int depth) {
			this.id = id;
			this.method = copy(method);
			this.depth = depth;
		}

		public String getId() {
			return id;
		}

		public JadxNodeRef getMethod() {
			return copy(method);
		}

		public int getDepth() {
			return depth;
		}

		public List<Stop> getStops() {
			return Collections.unmodifiableList(stops);
		}

		void stop(Stop stop) {
			if (!stops.contains(stop)) {
				stops.add(stop);
			}
		}
	}

	public static final class Edge {
		private String caller;
		private String callee;
		private int offset;
		private String opcode;

		private Edge() {
		}

		Edge(String caller, String callee, int offset, String opcode) {
			this.caller = caller;
			this.callee = callee;
			this.offset = offset;
			this.opcode = opcode;
		}

		public String getCaller() {
			return caller;
		}

		public String getCallee() {
			return callee;
		}

		public int getOffset() {
			return offset;
		}

		public String getOpcode() {
			return opcode;
		}
	}

	private List<Node> nodes = new ArrayList<>();
	private List<Edge> edges = new ArrayList<>();
	private int depthLimit;
	private String scope = "Incoming declared bytecode invocations from the current usage index; "
			+ "not runtime execution or external reachability. Reflection, native code and dynamic dispatch remain unresolved.";

	private CallPath() {
	}

	CallPath(int depthLimit) {
		this.depthLimit = depthLimit;
	}

	public List<Node> getNodes() {
		return Collections.unmodifiableList(nodes);
	}

	public List<Edge> getEdges() {
		return Collections.unmodifiableList(edges);
	}

	public Node node(String id) {
		return nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow();
	}

	Node add(JadxNodeRef ref, int depth) {
		Node node = new Node("P" + (nodes.size() + 1), ref, depth);
		nodes.add(node);
		return node;
	}

	void add(Edge edge) {
		edges.add(edge);
	}

	public boolean hasUnexpanded() {
		return nodes.stream().flatMap(n -> n.stops.stream()).anyMatch(s -> s != Stop.NO_INDEXED_CALLERS);
	}

	public String summary() {
		return String.format(AiText.str("api.path.summary"), nodes.size(), edges.size(), depthLimit)
				+ " · " + AiText.str(hasUnexpanded() ? "api.path.partial" : "api.path.index_exhausted");
	}

	public String describe() {
		StringBuilder text = new StringBuilder(AiText.str("api.path.title")).append('\n').append(summary()).append('\n')
				.append(AiText.str("api.path.boundary")).append('\n');
		for (Node node : nodes) {
			text.append(node.id).append(" | ").append(node.method).append('\n');
			for (Stop stop : node.stops) {
				text.append("  ").append(stop.label()).append('\n');
			}
		}
		for (Edge edge : edges) {
			text.append(edge.caller).append(" -> ").append(edge.callee).append(" | ").append(edge.opcode)
					.append(" @ ").append(edge.offset).append('\n');
		}
		return text.toString();
	}

	private static JadxNodeRef copy(JadxNodeRef ref) {
		return new JadxNodeRef(ref.getType(), ref.getDeclaringClass(), ref.getShortId());
	}
}
