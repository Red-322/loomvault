package io.loomvault.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declarative budget for a workflow or a single node.
 *
 * <p>A value of {@code 0} (or {@code null} for cost) means "not constrained". Budgets compose:
 * the workflow-level policy guards the run as a whole, while a node-level policy guards a single
 * expensive step.</p>
 *
 * @param maxTotalTokens  hard cap on input+output tokens across the guarded scope; 0 = unlimited
 * @param maxLlmCalls     hard cap on model calls; 0 = unlimited
 * @param maxCostUsd      hard cap on estimated spend in USD; {@code null} = unlimited
 * @param maxDurationMs   wall-clock cap in milliseconds; 0 = unlimited
 * @param onExceed        strategy applied when a line is crossed
 * @param warnThreshold   fraction in {@code (0,1)} at which a BUDGET_WARNING event is emitted
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BudgetPolicy(
        long maxTotalTokens,
        long maxLlmCalls,
        BigDecimal maxCostUsd,
        long maxDurationMs,
        BudgetStrategy onExceed,
        double warnThreshold
) {

    /** Shared instance used when nothing is constrained. */
    public static final BudgetPolicy UNLIMITED =
            new BudgetPolicy(0L, 0L, null, 0L, BudgetStrategy.WARN, 0.8d);

    public BudgetPolicy {
        if (onExceed == null) {
            onExceed = BudgetStrategy.WARN;
        }
        if (warnThreshold <= 0d || warnThreshold >= 1d) {
            warnThreshold = 0.8d;
        }
        if (maxTotalTokens < 0 || maxLlmCalls < 0 || maxDurationMs < 0) {
            throw new IllegalArgumentException("budget limits must not be negative");
        }
        if (maxCostUsd != null && maxCostUsd.signum() < 0) {
            throw new IllegalArgumentException("maxCostUsd must not be negative");
        }
    }

    public static BudgetPolicy unlimited() {
        return UNLIMITED;
    }

    public static BudgetPolicy ofTokens(long maxTotalTokens, BudgetStrategy strategy) {
        return new BudgetPolicy(maxTotalTokens, 0L, null, 0L, strategy, 0.8d);
    }

    public static BudgetPolicy ofCost(BigDecimal maxCostUsd, BudgetStrategy strategy) {
        return new BudgetPolicy(0L, 0L, maxCostUsd, 0L, strategy, 0.8d);
    }

    public boolean isUnlimited() {
        return maxTotalTokens <= 0 && maxLlmCalls <= 0 && maxDurationMs <= 0
                && (maxCostUsd == null || maxCostUsd.signum() == 0);
    }

    /** Returns true when this policy constrains token spend at all. */
    public boolean constrainsTokens() {
        return maxTotalTokens > 0;
    }

    public boolean constrainsCost() {
        return maxCostUsd != null && maxCostUsd.signum() > 0;
    }

    public boolean constrainsCalls() {
        return maxLlmCalls > 0;
    }

    public boolean constrainsDuration() {
        return maxDurationMs > 0;
    }

    public Builder toBuilder() {
        return new Builder()
                .maxTotalTokens(maxTotalTokens)
                .maxLlmCalls(maxLlmCalls)
                .maxCostUsd(maxCostUsd)
                .maxDurationMs(maxDurationMs)
                .onExceed(onExceed)
                .warnThreshold(warnThreshold);
    }

    /**
     * Fluent builder: budget policies end up in hand-written YAML far more often than in code,
     * and a readable builder keeps the demo definitions honest.
     */
    public static final class Builder {
        private long maxTotalTokens;
        private long maxLlmCalls;
        private BigDecimal maxCostUsd;
        private long maxDurationMs;
        private BudgetStrategy onExceed = BudgetStrategy.WARN;
        private double warnThreshold = 0.8d;

        public Builder maxTotalTokens(long value) {
            this.maxTotalTokens = value;
            return this;
        }

        public Builder maxLlmCalls(long value) {
            this.maxLlmCalls = value;
            return this;
        }

        public Builder maxCostUsd(BigDecimal value) {
            this.maxCostUsd = value;
            return this;
        }

        public Builder maxCostUsd(String value) {
            this.maxCostUsd = value == null ? null : new BigDecimal(value);
            return this;
        }

        public Builder maxDurationMs(long value) {
            this.maxDurationMs = value;
            return this;
        }

        public Builder maxDuration(java.time.Duration value) {
            this.maxDurationMs = value == null ? 0L : value.toMillis();
            return this;
        }

        public Builder onExceed(BudgetStrategy value) {
            this.onExceed = value;
            return this;
        }

        public Builder warnThreshold(double value) {
            this.warnThreshold = value;
            return this;
        }

        public BudgetPolicy build() {
            return new BudgetPolicy(maxTotalTokens, maxLlmCalls, maxCostUsd, maxDurationMs, onExceed, warnThreshold);
        }
    }

    /** Convenience for turning this policy into an ordered map (used by API responses and docs). */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxTotalTokens", maxTotalTokens);
        map.put("maxLlmCalls", maxLlmCalls);
        map.put("maxCostUsd", maxCostUsd);
        map.put("maxDurationMs", maxDurationMs);
        map.put("onExceed", onExceed.name());
        map.put("warnThreshold", warnThreshold);
        return map;
    }
}
