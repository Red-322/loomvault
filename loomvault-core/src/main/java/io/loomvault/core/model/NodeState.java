package io.loomvault.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Materialised state of a single node inside one execution.
 *
 * <p>This is the unit of durability: after every state change the engine persists the node state
 * and appends a journal entry in the same logical step, so a crash can never leave the two
 * disagreeing in a way that matters.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeState(
        String nodeId,
        NodeStatus status,
        int attempt,
        Map<String, Object> output,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Usage usage
) {

    public NodeState {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        status = status == null ? NodeStatus.PENDING : status;
        output = output == null ? Map.of() : Map.copyOf(output);
        usage = usage == null ? Usage.ZERO : usage;
    }

    public static NodeState pending(String nodeId) {
        return new NodeState(nodeId, NodeStatus.PENDING, 0, Map.of(), null, null, null, null, Usage.ZERO);
    }

    public NodeState withStatus(NodeStatus value) {
        return new NodeState(nodeId, value, attempt, output, errorCode, errorMessage, startedAt, finishedAt, usage);
    }

    public NodeState running(int attemptNo, Instant at) {
        return new NodeState(nodeId, NodeStatus.RUNNING, attemptNo, Map.of(),
                null, null, at, null, usage);
    }

    public NodeState completed(Map<String, Object> result, Usage recorded, Instant at) {
        return new NodeState(nodeId, NodeStatus.COMPLETED, attempt, result,
                null, null, startedAt, at, recorded == null ? usage : recorded);
    }

    public NodeState skipped(String reason) {
        return new NodeState(nodeId, NodeStatus.SKIPPED, attempt, Map.of(),
                null, reason, startedAt, finishedAt, usage);
    }

    public NodeState failed(String code, String message, Instant at) {
        return new NodeState(nodeId, NodeStatus.FAILED, attempt, Map.of(),
                code, message, startedAt, at, usage);
    }

    public NodeState awaitingApproval(Instant at) {
        return new NodeState(nodeId, NodeStatus.WAITING_APPROVAL, attempt, Map.of(),
                null, null, at, null, usage);
    }

    public boolean isCompleted() {
        return status == NodeStatus.COMPLETED;
    }
}
