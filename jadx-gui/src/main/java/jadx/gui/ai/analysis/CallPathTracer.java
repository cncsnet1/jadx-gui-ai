package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import jadx.api.JadxDecompiler;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.plugins.input.insns.InsnIndexType;
import jadx.core.dex.info.MethodInfo;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiContext;

/**
 * Reverse BFS over the usage index, verifying each edge against its caller's original instructions.
 */
public final class CallPathTracer {

	interface Relations {
		default void begin() {
		}

		Expansion incoming(JadxNodeRef callee, AiCancellation token) throws InterruptedException;
	}

	static final class Call {
		final JadxNodeRef caller;
		final int offset;
		final String opcode;

		Call(JadxNodeRef caller, int offset, String opcode) {
			this.caller = caller;
			this.offset = offset;
			this.opcode = opcode;
		}
	}

	static final class Expansion {
		final List<Call> calls = new ArrayList<>();
		final List<CallPath.Stop> stops = new ArrayList<>();
	}

	private final Relations relations;
	private final int depthLimit;
	private final int nodeLimit;
	private final int edgeLimit;

	public CallPathTracer(JadxDecompiler decompiler) {
		this(new BytecodeRelations(decompiler), 4, 48, 96);
	}

	CallPathTracer(Relations relations, int depthLimit, int nodeLimit, int edgeLimit) {
		this.relations = relations;
		this.depthLimit = depthLimit;
		this.nodeLimit = nodeLimit;
		this.edgeLimit = edgeLimit;
	}

	public CallPath trace(JadxNodeRef target, AiCancellation token) throws InterruptedException {
		if (target.getType() != RefType.METHOD) {
			throw new IllegalArgumentException("Call paths require an exact method reference");
		}
		token.check();
		relations.begin();
		CallPath result = new CallPath(depthLimit);
		Map<JadxNodeRef, CallPath.Node> visited = new LinkedHashMap<>();
		visited.put(target, result.add(target, 0));
		for (int index = 0; index < result.getNodes().size(); index++) {
			token.check();
			CallPath.Node current = result.getNodes().get(index);
			if (current.getDepth() >= depthLimit) {
				current.stop(CallPath.Stop.DEPTH);
				continue;
			}
			Expansion expansion = relations.incoming(current.getMethod(), token);
			expansion.stops.forEach(current::stop);
			for (Call call : expansion.calls) {
				token.check();
				CallPath.Node caller = visited.get(call.caller);
				String callerId = caller == null ? "" : caller.getId();
				if (caller != null && result.getEdges().stream().anyMatch(e -> e.getCaller().equals(callerId)
						&& e.getCallee().equals(current.getId()))) {
					continue;
				}
				if (result.getEdges().size() >= edgeLimit) {
					current.stop(CallPath.Stop.EDGES);
					break;
				}
				if (caller == null) {
					if (visited.size() >= nodeLimit) {
						current.stop(CallPath.Stop.NODES);
						continue;
					}
					caller = result.add(call.caller, current.getDepth() + 1);
					visited.put(call.caller, caller);
				}
				result.add(new CallPath.Edge(caller.getId(), current.getId(), call.offset, call.opcode));
			}
		}
		token.check();
		return result;
	}

	private static final class BytecodeRelations implements Relations {
		private final JadxDecompiler decompiler;
		private final Map<JadxNodeRef, Instructions> cache = new HashMap<>();
		private long deadline;
		private int reads;
		private int units;

		BytecodeRelations(JadxDecompiler decompiler) {
			this.decompiler = decompiler;
		}

		@Override
		public void begin() {
			cache.clear();
			reads = 0;
			units = 0;
			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		}

		@Override
		public Expansion incoming(JadxNodeRef callee, AiCancellation token) throws InterruptedException {
			Expansion result = new Expansion();
			token.check();
			JavaNode node = AiContext.resolve(decompiler, callee);
			if (!(node instanceof JavaMethod)) {
				result.stops.add(CallPath.Stop.UNRESOLVED);
				return result;
			}
			var usages = ((JavaMethod) node).getMethodNode().getUseIn();
			if (usages.isEmpty()) {
				result.stops.add(CallPath.Stop.NO_INDEXED_CALLERS);
			}
			int visited = 0;
			for (var usage : usages) {
				token.check();
				if (++visited > 32) {
					result.stops.add(CallPath.Stop.REFERENCES);
					break;
				}
				if (System.nanoTime() >= deadline) {
					result.stops.add(CallPath.Stop.TIME);
					break;
				}
				JadxNodeRef caller = new JadxNodeRef(RefType.METHOD, usage.getParentClass().getRawName(),
						usage.getMethodInfo().getShortId());
				Instructions instructions = cache.get(caller);
				if (instructions == null) {
					instructions = read(usage, token);
					cache.put(caller, instructions);
				}
				Call site = instructions.targets.get(callee);
				if (site != null) {
					result.calls.add(new Call(caller, site.offset, site.opcode));
				} else {
					result.stops.add(instructions.stop == null ? CallPath.Stop.UNVERIFIED : instructions.stop);
				}
			}
			return result;
		}

		private Instructions read(jadx.core.dex.nodes.MethodNode method, AiCancellation token) throws InterruptedException {
			Instructions result = new Instructions();
			var reader = method.getCodeReader();
			if (reader == null) {
				result.stop = CallPath.Stop.UNRESOLVED;
				return result;
			}
			if (reader.getUnitsCount() > 200_000) {
				result.stop = CallPath.Stop.METHOD_SIZE;
				return result;
			}
			if (++reads > 128 || (units += reader.getUnitsCount()) > 1_000_000) {
				result.stop = CallPath.Stop.READS;
				return result;
			}
			try {
				reader.copy().visitInstructions(insn -> {
					if (token.isCancelled() || Thread.currentThread().isInterrupted()) {
						throw new CancellationException();
					}
					if (System.nanoTime() >= deadline) {
						throw new DeadlineReached();
					}
					insn.decode();
					String opcode = insn.getOpcode().name();
					// Method handles and dynamic call sites do not establish this declared invocation edge.
					if (opcode.startsWith("INVOKE_") && !opcode.startsWith("INVOKE_POLYMORPHIC")
							&& !opcode.startsWith("INVOKE_CUSTOM") && insn.getIndexType() == InsnIndexType.METHOD_REF) {
						var ref = insn.getIndexAsMethod();
						if (ref != null) {
							ref.load();
							MethodInfo info = MethodInfo.fromRef(decompiler.getRoot(), ref);
							JadxNodeRef target = new JadxNodeRef(RefType.METHOD, info.getDeclClass().getRawName(), info.getShortId());
							if (result.targets.size() < 256) {
								result.targets.putIfAbsent(target, new Call(null, insn.getOffset(), opcode));
							} else {
								result.stop = CallPath.Stop.METHOD_SIZE;
							}
						}
					}
				});
			} catch (DeadlineReached e) {
				result.stop = CallPath.Stop.TIME;
			} catch (RuntimeException e) {
				token.check();
				result.stop = CallPath.Stop.UNRESOLVED;
			}
			token.check();
			return result;
		}
	}

	private static final class Instructions {
		private final Map<JadxNodeRef, Call> targets = new HashMap<>();
		private CallPath.Stop stop;
	}

	private static final class DeadlineReached extends RuntimeException {
	}
}
