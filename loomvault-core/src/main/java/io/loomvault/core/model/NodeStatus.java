package io.loomvault.core.model;

/**
 * Lifecycle of a single node inside an execution.
 *
 * <p>On a cold resume the engine rebuilds this state from the journal: nodes whose completion was
 * recorded are restored as {@link #COMPLETED} with their recorded output, everything else starts
 * from {@link #PENDING} again.</p>
 */
public enum NodeStatus {

    /** Not yet reached (predecessors still running). */
    PENDING,
    /** All predecessors settled; queued for execution. */
    READY,
    /** Currently executing. */
    RUNNING,
    /** Failed at least once and is waiting out its backoff before the next attempt. */
    RETRYING,
    /** Parked because the node needs a human decision. */
    WAITING_APPROVAL,
    /** Finished successfully; output is durable. */
    COMPLETED,
    /** Exhausted retries (or was rejected). Absence of a successful output is expected. */
    FAILED,
    /** Never ran because an upstream branch was not taken, or the execution aborted first. */
    SKIPPED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED;
    }

    public boolean isSettled() {
        return isTerminal();
    }
}
