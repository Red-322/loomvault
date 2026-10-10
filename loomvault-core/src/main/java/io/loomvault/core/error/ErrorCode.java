package io.loomvault.core.error;

/**
 * Stable, machine-readable error taxonomy for the whole engine.
 *
 * <p>Every failure surfaced by LoomVault carries one of these codes. The REST layer maps them
 * onto HTTP status codes, and operators can key alerts off the {@code code} field without
 * parsing free-form messages.</p>
 */
public enum ErrorCode {

    // ---- Workflow definition / graph validation -------------------------------------------
    /** The workflow contains a directed cycle and therefore cannot be scheduled. */
    GRAPH_CYCLE_DETECTED,
    /** Two nodes share the same id inside one workflow definition. */
    GRAPH_DUPLICATE_NODE,
    /** An edge references a node id that does not exist in the node list. */
    GRAPH_UNKNOWN_EDGE_ENDPOINT,
    /** The workflow has no node without incoming edges, so nothing can ever start. */
    GRAPH_NO_ENTRY_POINT,
    /** The definition is structurally invalid for reasons not covered above. */
    INVALID_WORKFLOW,

    // ---- Execution lifecycle ---------------------------------------------------------------
    /** No execution exists for the supplied id. */
    EXECUTION_NOT_FOUND,
    /** The execution is in a terminal state and cannot be driven further. */
    EXECUTION_NOT_RESUMABLE,
    /** A node failed after exhausting its retry policy. */
    NODE_FAILED,
    /** A node exceeded its per-node timeout budget. */
    NODE_TIMEOUT,
    /** No {@code NodeHandler} was registered for the node kind or tool name. */
    NODE_NO_HANDLER,
    /** The execution parked because an approval node is waiting for a human decision. */
    APPROVAL_REQUIRED,
    /** A human rejected the approval request; the execution terminates as failed. */
    APPROVAL_REJECTED,
    /** The execution was cancelled by an operator or by policy. */
    EXECUTION_CANCELLED,

    // ---- Budget governance -----------------------------------------------------------------
    /** The execution exceeded one of its token / cost / call budgets and the strategy is FAIL. */
    BUDGET_EXCEEDED,
    /** A budget threshold was crossed but the execution continues under a fallback strategy. */
    BUDGET_WARNING,

    // ---- Replay / durability ---------------------------------------------------------------
    /** Replayed node output does not match what the journal recorded. */
    REPLAY_DIVERGENCE,
    /** The append-only journal is malformed (missing entries, non-monotonic sequence). */
    JOURNAL_CORRUPTED,

    // ---- Expression engine -----------------------------------------------------------------
    /** The condition expression could not be parsed. */
    EXPRESSION_SYNTAX_ERROR,
    /** The expression parsed but evaluation failed (unknown path, type mismatch, division by zero). */
    EXPRESSION_EVALUATION_ERROR,
    /** A prompt template referenced a variable that is not available in the context. */
    TEMPLATE_RENDER_ERROR,

    // ---- Model gateway ---------------------------------------------------------------------
    /** The upstream model provider returned an error or was unreachable. */
    LLM_CALL_FAILED,
    /** The circuit breaker is open and refused the call without touching the provider. */
    LLM_CIRCUIT_OPEN,

    // ---- Infrastructure --------------------------------------------------------------------
    /** A persistence operation failed. */
    PERSISTENCE_ERROR,
    /** Optimistic locking or a distributed lock rejected the write. */
    CONCURRENCY_CONFLICT,
    /** Anything that should never happen. Always accompanied by a stack trace. */
    INTERNAL_ERROR
}
