package io.loomvault.core.budget;

import io.loomvault.core.model.BudgetPolicy;
import io.loomvault.core.model.Usage;

/**
 * Outcome of evaluating a budget policy against accumulated usage.
 *
 * @param status    how severe the situation is
 * @param dimension which limit triggered the status ({@code tokens}, {@code costUsd},
 *                  {@code llmCalls}, {@code durationMs}) or {@code null} when {@link Status#OK}
 * @param usage     usage snapshot at evaluation time
 * @param limit     the breached (or approached) limit rendered as text; {@code null} when {@link Status#OK}
 * @param policy    the policy that was evaluated
 */
public record BudgetDecision(Status status, String dimension, Usage usage, String limit, BudgetPolicy policy) {

    public enum Status {
        /** Comfortably inside every limit. */
        OK,
        /** At or above the warning threshold but below the hard limit. */
        WARNING,
        /** A hard limit was crossed; {@link BudgetPolicy#onExceed()} decides what happens next. */
        EXCEEDED
    }

    public static BudgetDecision ok(Usage usage, BudgetPolicy policy) {
        return new BudgetDecision(Status.OK, null, usage, null, policy);
    }

    public static BudgetDecision warning(String dimension, Usage usage, String limit, BudgetPolicy policy) {
        return new BudgetDecision(Status.WARNING, dimension, usage, limit, policy);
    }

    public static BudgetDecision exceeded(String dimension, Usage usage, String limit, BudgetPolicy policy) {
        return new BudgetDecision(Status.EXCEEDED, dimension, usage, limit, policy);
    }

    public boolean isExceeded() {
        return status == Status.EXCEEDED;
    }

    public boolean isWarning() {
        return status == Status.WARNING;
    }

    /** Human-readable summary used in journal payloads and API responses. */
    public String describe() {
        return switch (status) {
            case OK -> "within budget";
            case WARNING -> "approaching the %s limit (%s of %s)".formatted(dimension, measured(), limit);
            case EXCEEDED -> "%s limit exceeded (%s > %s); strategy=%s".formatted(
                    dimension, measured(), limit, policy.onExceed());
        };
    }

    private String measured() {
        return switch (dimension == null ? "" : dimension) {
            case "tokens" -> String.valueOf(usage.totalTokens());
            case "costUsd" -> usage.costUsd().toPlainString();
            case "llmCalls" -> String.valueOf(usage.llmCalls());
            default -> "-";
        };
    }
}
