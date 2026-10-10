package io.loomvault.core.model;

/**
 * What the engine should do the moment a budget line is crossed.
 *
 * <p>This is the difference between a demo and a production engine: an agent that burns $40 of
 * tokens at 3am must degrade or stop deterministically instead of being noticed on the monthly
 * invoice.</p>
 */
public enum BudgetStrategy {

    /** Abort the execution immediately and mark it {@code BUDGET_EXCEEDED}. */
    FAIL,

    /**
     * Keep going on a cheaper model. Requires the node to declare a {@code fallbackModel};
     * otherwise the engine falls back to {@link #FAIL} for that node.
     */
    DEGRADE,

    /**
     * Shrink the prompt context (drop the oldest messages above a floor) and retry the call.
     * Used when the input dominates the cost.
     */
    TRUNCATE,

    /**
     * Record a warning event and continue without changing behaviour.
     * Useful for observability-first rollouts before enforcement is switched on.
     */
    WARN
}
