package io.loomvault.core.graph;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.GraphValidationException;
import io.loomvault.core.model.EdgeDefinition;
import io.loomvault.core.model.NodeDefinition;
import io.loomvault.core.model.WorkflowDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A validated, queryable view of a {@link WorkflowDefinition}.
 *
 * <p>Construction performs the full structural check once — unique ids, known edge endpoints, no
 * self-loops, no cycles — and computes the topological order and the root set up front. The
 * scheduler then only deals with cheap adjacency lookups, and every illegal graph is rejected
 * long before the first token is billed.</p>
 *
 * <p>Cycle reporting uses a three-colour DFS so the exception carries the actual offending path
 * ({@code a -> b -> c -> a}) instead of a generic "graph has a cycle" message.</p>
 */
public final class WorkflowGraph {

    private final WorkflowDefinition definition;
    private final Map<String, NodeDefinition> nodes;
    private final Map<String, List<EdgeDefinition>> outgoing;
    private final Map<String, List<EdgeDefinition>> incoming;
    private final List<String> roots;
    private final List<String> topologicalOrder;

    private WorkflowGraph(WorkflowDefinition definition,
                          Map<String, NodeDefinition> nodes,
                          Map<String, List<EdgeDefinition>> outgoing,
                          Map<String, List<EdgeDefinition>> incoming,
                          List<String> roots,
                          List<String> topologicalOrder) {
        this.definition = definition;
        this.nodes = nodes;
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.roots = roots;
        this.topologicalOrder = topologicalOrder;
    }

    // =========================================================================================
    // Construction
    // =========================================================================================

    /**
     * Builds and validates a graph.
     *
     * @throws GraphValidationException when the definition cannot be scheduled
     */
    public static WorkflowGraph of(WorkflowDefinition definition) {
        if (definition == null) {
            throw new GraphValidationException(ErrorCode.INVALID_WORKFLOW, "workflow definition is null", List.of());
        }
        if (definition.nodes().isEmpty()) {
            throw new GraphValidationException(ErrorCode.INVALID_WORKFLOW,
                    "workflow '" + definition.id() + "' has no nodes", List.of());
        }

        Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (NodeDefinition node : definition.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) {
                duplicates.add(node.id());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new GraphValidationException(ErrorCode.GRAPH_DUPLICATE_NODE,
                    "duplicate node ids: " + duplicates, duplicates);
        }

        Map<String, List<EdgeDefinition>> outgoing = new LinkedHashMap<>();
        Map<String, List<EdgeDefinition>> incoming = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> {
            outgoing.put(id, new ArrayList<>());
            incoming.put(id, new ArrayList<>());
        });

        List<String> unknownEndpoints = new ArrayList<>();
        for (EdgeDefinition edge : definition.edges()) {
            if (!nodes.containsKey(edge.from())) {
                unknownEndpoints.add(edge.from());
            }
            if (!nodes.containsKey(edge.to())) {
                unknownEndpoints.add(edge.to());
            }
        }
        if (!unknownEndpoints.isEmpty()) {
            throw new GraphValidationException(ErrorCode.GRAPH_UNKNOWN_EDGE_ENDPOINT,
                    "edges reference unknown node ids: " + new LinkedHashSet<>(unknownEndpoints),
                    List.copyOf(new LinkedHashSet<>(unknownEndpoints)));
        }
        for (EdgeDefinition edge : definition.edges()) {
            outgoing.get(edge.from()).add(edge);
            incoming.get(edge.to()).add(edge);
        }

        List<String> roots = nodes.keySet().stream()
                .filter(id -> incoming.get(id).isEmpty())
                .toList();
        if (roots.isEmpty()) {
            throw new GraphValidationException(ErrorCode.GRAPH_NO_ENTRY_POINT,
                    "workflow has no entry point: every node has at least one incoming edge", List.of());
        }

        List<String> order = detectCycleAndSort(nodes, outgoing, incoming);
        return new WorkflowGraph(definition, nodes, outgoing, incoming, roots, order);
    }

    /**
     * Kahn's algorithm for the topological order. Remaining nodes after the queue drains form the
     * cyclic region; a DFS then extracts one concrete cycle for the error message.
     */
    private static List<String> detectCycleAndSort(Map<String, NodeDefinition> nodes,
                                                   Map<String, List<EdgeDefinition>> outgoing,
                                                   Map<String, List<EdgeDefinition>> incoming) {
        Map<String, Integer> remaining = new HashMap<>();
        nodes.keySet().forEach(id -> remaining.put(id, incoming.get(id).size()));

        Deque<String> queue = new ArrayDeque<>();
        nodes.keySet().stream().filter(id -> remaining.get(id) == 0).forEach(queue::add);

        List<String> order = new ArrayList<>(nodes.size());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            order.add(current);
            for (EdgeDefinition edge : outgoing.get(current)) {
                int left = remaining.merge(edge.to(), -1, Integer::sum);
                if (left == 0) {
                    queue.add(edge.to());
                }
            }
        }

        if (order.size() != nodes.size()) {
            List<String> cycle = findCycle(nodes.keySet(), outgoing, remaining);
            throw new GraphValidationException(ErrorCode.GRAPH_CYCLE_DETECTED,
                    "workflow contains a cycle: " + String.join(" -> ", cycle), cycle,
                    Map.of("cycle", cycle));
        }
        return List.copyOf(order);
    }

    /** Three-colour DFS over the leftover (cyclic) nodes, returning one concrete cycle. */
    private static List<String> findCycle(Set<String> allNodes,
                                          Map<String, List<EdgeDefinition>> outgoing,
                                          Map<String, Integer> remaining) {
        Set<String> suspect = new LinkedHashSet<>();
        remaining.forEach((id, degree) -> {
            if (degree > 0) {
                suspect.add(id);
            }
        });

        Map<String, Integer> colour = new HashMap<>(); // 0 = white, 1 = grey, 2 = black
        Deque<String> path = new ArrayDeque<>();
        List<String> cycle = new ArrayList<>();
        for (String start : suspect) {
            if (colour.getOrDefault(start, 0) == 0
                    && dfs(start, outgoing, suspect, colour, path, cycle)) {
                return List.copyOf(cycle);
            }
        }
        return List.copyOf(suspect);
    }

    private static boolean dfs(String node,
                               Map<String, List<EdgeDefinition>> outgoing,
                               Set<String> suspect,
                               Map<String, Integer> colour,
                               Deque<String> path,
                               List<String> cycleOut) {
        colour.put(node, 1);
        path.push(node);
        for (EdgeDefinition edge : outgoing.get(node)) {
            String next = edge.to();
            if (!suspect.contains(next)) {
                continue;
            }
            int c = colour.getOrDefault(next, 0);
            if (c == 1) {
                // Back edge found: the cycle is the path segment from `next` up to `node`,
                // closed by `next` again. `path` iterates newest-first, hence the reversal.
                List<String> segment = new ArrayList<>();
                for (String visited : path) {
                    segment.add(visited);
                    if (visited.equals(next)) {
                        break;
                    }
                }
                Collections.reverse(segment);
                cycleOut.addAll(segment);
                cycleOut.add(next);
                return true;
            }
            if (c == 0 && dfs(next, outgoing, suspect, colour, path, cycleOut)) {
                return true;
            }
        }
        path.pop();
        colour.put(node, 2);
        return false;
    }

    // =========================================================================================
    // Queries used by the scheduler
    // =========================================================================================

    public WorkflowDefinition definition() {
        return definition;
    }

    public String workflowId() {
        return definition.id();
    }

    public Collection<NodeDefinition> nodes() {
        return nodes.values();
    }

    public NodeDefinition node(String nodeId) {
        NodeDefinition node = nodes.get(nodeId);
        if (node == null) {
            throw new GraphValidationException(ErrorCode.INVALID_WORKFLOW,
                    "workflow '" + workflowId() + "' has no node '" + nodeId + "'", List.of(nodeId));
        }
        return node;
    }

    public boolean contains(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    public Set<String> nodeIds() {
        return nodes.keySet();
    }

    /** Outgoing edges, condition included: the scheduler evaluates them when the node completes. */
    public List<EdgeDefinition> outgoing(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    public List<EdgeDefinition> incoming(String nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }

    public List<String> successors(String nodeId) {
        return outgoing(nodeId).stream().map(EdgeDefinition::to).distinct().toList();
    }

    public List<String> predecessors(String nodeId) {
        return incoming(nodeId).stream().map(EdgeDefinition::from).distinct().toList();
    }

    /** Nodes with no incoming edges — the scheduler's initial ready set. */
    public List<String> roots() {
        return roots;
    }

    /** Total number of incoming edges: the counter a node must drain before it becomes ready. */
    public int predecessorCount(String nodeId) {
        return incoming.getOrDefault(nodeId, List.of()).size();
    }

    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    /**
     * Computes which nodes can never run given a set of permanently skipped nodes. Used to
     * propagate "branch not taken" through the graph so the final state contains no PENDING
     * leftovers on untaken branches.
     */
    public Set<String> unreachableFrom(Set<String> skipped, Set<String> settled) {
        Set<String> unreachable = new LinkedHashSet<>(skipped);
        for (String nodeId : topologicalOrder) {
            if (settled.contains(nodeId) || unreachable.contains(nodeId)) {
                continue;
            }
            List<String> preds = predecessors(nodeId);
            if (!preds.isEmpty() && preds.stream().allMatch(unreachable::contains)) {
                unreachable.add(nodeId);
            }
        }
        return unreachable;
    }
}
