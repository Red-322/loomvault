package io.loomvault.core.model;

/**
 * Lifecycle of a single execution instance.
 *
 * <pre>
 *   PENDING ──▶ RUNNING ──┬──▶ SUCCEEDED
 *                         ├──▶ FAILED
 *                         ├──▶ CANCELLED
 *                         ├──▶ BUDGET_EXCEEDED
 *                         └──▶ WAITING_APPROVAL ──(resume)──▶ RUNNING
 * </pre>
 *
 * <p>{@link #WAITING_APPROVAL} is the only non-terminal state that a process restart can leave
 * behind on purpose: the execution is parked, fully persisted, and will be picked up again when a
 * human calls the approve/reject endpoint.</p>
 */
public enum ExecutionStatus {

    /** Created but not yet driven by the engine. */
    PENDING,
    /** Actively being scheduled or executed. */
    RUNNING,
    /** Parked on an approval node; resumable once a human decides. */
    WAITING_APPROVAL,
    /** Every reachable node completed successfully. */
    SUCCEEDED,
    /** A non-optional node failed, or the workflow itself was rejected during approval. */
    FAILED,
    /** An operator cancelled the execution. */
    CANCELLED,
    /** The budget strategy was FAIL and a budget line was crossed. */
    BUDGET_EXCEEDED;

    /** Terminal states can never be driven further. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == BUDGET_EXCEEDED;
    }

    /** States from which the engine can pick the execution back up. */
    public boolean isResumable() {
        return this == PENDING || this == RUNNING || this == WAITING_APPROVAL;
    }

    public boolean isActive() {
        return this == RUNNING || this == WAITING_APPROVAL;
    }
}
