package io.loomvault.core.journal;

/**
 * The vocabulary of the append-only journal.
 *
 * <p>Everything that can change an execution's state goes through one of these entries. Two
 * properties matter and are enforced by the engine:</p>
 * <ol>
 *   <li><b>Append-only.</b> Entries are never updated or deleted. Corrections are new entries.</li>
 *   <li><b>Self-sufficient.</b> Each entry carries the full payload needed to reconstruct the
 *       state change — replay never needs to consult the live (and possibly already-changed)
 *       database rows.</li>
 * </ol>
 */
public enum JournalEntryType {

    /** First entry of an execution: workflow id/version, inputs, definition fingerprint. */
    EXECUTION_STARTED,

    /** A cold resume began: how many entries were replayed, which nodes were restored. */
    EXECUTION_RESUMED,

    /** Terminal success. Carries the aggregated outputs. */
    EXECUTION_FINISHED,

    /** Terminal failure, cancellation or budget stop. Carries the reason. */
    EXECUTION_STOPPED,

    /** A node became ready and was queued. Useful for latency analysis of scheduling. */
    NODE_SCHEDULED,

    /** An attempt began. This is the marker that tells the recovery planner a side-effect window opened. */
    NODE_STARTED,

    /** A node produced its output. The output payload here is what replay feeds downstream nodes. */
    NODE_COMPLETED,

    /** An attempt failed. Retries append one of these per attempt. */
    NODE_FAILED,

    /** A retry was scheduled after backoff; carries the delay actually applied. */
    NODE_RETRY_SCHEDULED,

    /** A branch was not taken, or the execution stopped before this node could run. */
    NODE_SKIPPED,

    /** An approval node parked the execution. */
    APPROVAL_REQUESTED,

    /** A human approved. The node completes with the approver's note as output. */
    APPROVAL_GRANTED,

    /** A human rejected. The execution terminates as failed. */
    APPROVAL_REJECTED,

    /** A budget threshold was crossed; the execution continues (strategy WARN/DEGRADE/TRUNCATE). */
    BUDGET_WARNING,

    /** A budget line was crossed and the execution was stopped (strategy FAIL). */
    BUDGET_EXCEEDED,

    /** A model call was served from the content-addressed cache: zero tokens, zero dollars. */
    CACHE_HIT,

    /** A recovery pass detected a non-idempotent node that had been interrupted mid-flight. */
    RECOVERY_WARNING
}
