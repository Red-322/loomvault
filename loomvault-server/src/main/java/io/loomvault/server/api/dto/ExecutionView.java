package io.loomvault.server.api.dto;

import io.loomvault.core.graph.WorkflowGraph;
import io.loomvault.core.model.Execution;
import io.loomvault.core.model.NodeDefinition;
import io.loomvault.core.model.NodeState;
import io.loomvault.core.model.NodeStatus;
import io.loomvault.core.model.Usage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read model for one execution, optimised for the control-room UI.
 *
 * <p>Flattens what the API needs (execution + node states + graph metadata) into a single object
 * so the frontend can render a run with one request. Node entries always follow the workflow's
 * declaration order, not the database's — a graph that reshuffles between refreshes is
 * disorienting.</p>
 */
public record ExecutionView(
        String id,
        String workflowId,
        String workflowVersion,
        String status,
        boolean terminal,
        boolean active,
        boolean awaitingApproval,
        Map<String, Object> inputs,
        Map<String, Object> outputs,
        UsageView usage,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Long elapsedMs,
        int resumeCount,
        List<NodeView> nodes,
        List<String> pendingApprovals
) {

    /** Token/cost counters as the UI shows them. */
    public record UsageView(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long llmCalls,
            long cacheHits,
            BigDecimal costUsd
    ) {

        public static UsageView of(Usage usage) {
            Usage safe = usage == null ? Usage.ZERO : usage;
            return new UsageView(safe.inputTokens(), safe.outputTokens(), safe.totalTokens(),
                    safe.llmCalls(), safe.cacheHits(), safe.costUsd());
        }

        public static UsageView zero() {
            return of(Usage.ZERO);
        }
    }

    /** One node's state, merged with its static definition. */
    public record NodeView(
            String nodeId,
            String name,
            String kind,
            String status,
            int attempt,
            boolean optional,
            boolean idempotent,
            String timeout,
            Map<String, Object> output,
            UsageView usage,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs
    ) {

        public static NodeView of(NodeDefinition definition, NodeState state) {
            NodeState safe = state == null ? NodeState.pending(definition.id()) : state;
            return new NodeView(
                    definition.id(),
                    definition.name(),
                    definition.kind().name(),
                    safe.status().name(),
                    safe.attempt(),
                    definition.optional(),
                    definition.idempotent(),
                    format(definition.timeout()),
                    safe.output(),
                    UsageView.of(safe.usage()),
                    safe.errorCode(),
                    safe.errorMessage(),
                    safe.startedAt(),
                    safe.finishedAt(),
                    durationOf(safe));
        }
    }

    /** Builds the view for a freshly started (not yet persisted) execution. */
    public static ExecutionView pending(String executionId, String workflowId, String version) {
        return new ExecutionView(executionId, workflowId, version, "PENDING", false, true, false,
                Map.of(), Map.of(), UsageView.zero(), null, null,
                Instant.now(), null, null, null, 0, List.of(), List.of());
    }

    /**
     * Builds the full view.
     *
     * @param graph  the workflow graph, used to order nodes and recover definitions
     * @param states node states keyed by node id; missing entries render as PENDING
     */
    public static ExecutionView of(Execution execution, WorkflowGraph graph,
                                   Map<String, NodeState> states, boolean active, Instant now) {
        List<NodeView> nodes = new ArrayList<>();
        List<String> pending = new ArrayList<>();

        Collection<String> order = graph == null ? states.keySet() : graph.nodeIds();
        for (String nodeId : order) {
            NodeDefinition definition;
            if (graph != null && graph.contains(nodeId)) {
                definition = graph.node(nodeId);
            } else {
                definition = NodeDefinition.llm(nodeId, "unknown", "");
            }
            NodeState state = states.get(nodeId);
            nodes.add(NodeView.of(definition, state));
            if (state != null && state.status() == NodeStatus.WAITING_APPROVAL) {
                pending.add(nodeId);
            }
        }
        // Nodes present in state but absent from the graph (definition changed under us).
        states.forEach((nodeId, state) -> {
            if (graph != null && !graph.contains(nodeId)) {
                nodes.add(NodeView.of(NodeDefinition.llm(nodeId, "orphan", ""), state));
            }
        });
        nodes.sort(Comparator.comparing(node -> orderIndex(order, node.nodeId())));

        return new ExecutionView(
                execution.id(),
                execution.workflowId(),
                execution.workflowVersion(),
                execution.status().name(),
                execution.isTerminal(),
                active,
                !pending.isEmpty(),
                execution.inputs(),
                execution.outputs(),
                UsageView.of(execution.usage()),
                execution.failureCode(),
                execution.failureReason(),
                execution.createdAt(),
                execution.startedAt(),
                execution.finishedAt(),
                execution.elapsedMillis(now),
                execution.resumeCount(),
                List.copyOf(nodes),
                List.copyOf(pending));
    }

    private static int orderIndex(Collection<String> order, String nodeId) {
        int index = 0;
        for (String candidate : order) {
            if (candidate.equals(nodeId)) {
                return index;
            }
            index++;
        }
        return Integer.MAX_VALUE;
    }

    private static Long durationOf(NodeState state) {
        if (state.startedAt() == null) {
            return null;
        }
        Instant end = state.finishedAt() == null ? Instant.now() : state.finishedAt();
        return Math.max(0L, Duration.between(state.startedAt(), end).toMillis());
    }

    private static String format(Duration duration) {
        if (duration == null) {
            return null;
        }
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m" + (seconds % 60) + "s";
        }
        return (minutes / 60) + "h" + (minutes % 60) + "m";
    }
}
