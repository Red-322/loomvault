package io.loomvault.core.budget;

import io.loomvault.core.model.BudgetPolicy;
import io.loomvault.core.model.Usage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe accountant for one budget scope (the whole execution, or a single node).
 *
 * <p>Parallel branches finish concurrently and each of them records usage, so accumulation is a
 * lock-free CAS loop on an immutable {@link Usage}. Evaluation is separated from accumulation:
 * callers record as often as they like and evaluate only at decision points (after every model
 * call), which keeps the hot path free of policy logic.</p>
 *
 * <p>A ledger is deliberately cheap to create — nodes get a fresh one per attempt — so that
 * "budget" never becomes the reason a feature is skipped.</p>
 */
public final class BudgetLedger {

    private final String scope;
    private final BudgetPolicy policy;
    private final Clock clock;
    private final Instant createdAt;
    private final AtomicReference<Usage> usage;
    private final AtomicBoolean warningEmitted = new AtomicBoolean(false);

    public BudgetLedger(String scope, BudgetPolicy policy, Clock clock, Usage initialUsage, Instant startedAt) {
        this.scope = scope == null ? "workflow" : scope;
        this.policy = policy == null ? BudgetPolicy.UNLIMITED : policy;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.usage = new AtomicReference<>(initialUsage == null ? Usage.ZERO : initialUsage);
        this.createdAt = startedAt == null ? this.clock.instant() : startedAt;
    }

    public BudgetLedger(String scope, BudgetPolicy policy, Clock clock) {
        this(scope, policy, clock, Usage.ZERO, null);
    }

    public static BudgetLedger unlimited(String scope, Clock clock) {
        return new BudgetLedger(scope, BudgetPolicy.UNLIMITED, clock);
    }

    /**
     * Rebuilds a ledger that already contains billed usage, as happens when an execution is
     * resumed from its journal. The original start time is preserved so that the wall-clock
     * limit keeps counting from the first attempt rather than resetting on every restart — a
     * crash loop must not be able to run forever by dying often enough.
     */
    public static BudgetLedger resuming(String scope, BudgetPolicy policy, Clock clock,
                                        Usage replayedUsage, Instant originalStart) {
        return new BudgetLedger(scope, policy, clock, replayedUsage, originalStart);
    }

    // ---- Accumulation ------------------------------------------------------------------------

    /** Records usage produced by one operation. Never blocks, never validates limits. */
    public void record(Usage delta) {
        if (delta == null || delta.isZero()) {
            return;
        }
        usage.updateAndGet(current -> current.plus(delta));
    }

    public Usage snapshot() {
        return usage.get();
    }

    public BudgetPolicy policy() {
        return policy;
    }

    public String scope() {
        return scope;
    }

    public Duration elapsed() {
        return Duration.between(createdAt, clock.instant());
    }

    // ---- Evaluation --------------------------------------------------------------------------

    /**
     * Evaluates the policy against the current snapshot.
     *
     * <p>Order of precedence: hard limits first (tokens, cost, calls, wall clock), then warning
     * thresholds. The returned decision is pure data — the engine decides whether to stop,
     * degrade or merely log.</p>
     */
    public BudgetDecision evaluate() {
        Usage u = usage.get();

        if (policy.constrainsTokens() && u.totalTokens() > policy.maxTotalTokens()) {
            return BudgetDecision.exceeded("tokens", u, String.valueOf(policy.maxTotalTokens()), policy);
        }
        if (policy.constrainsCost() && u.costUsd().compareTo(policy.maxCostUsd()) > 0) {
            return BudgetDecision.exceeded("costUsd", u, policy.maxCostUsd().toPlainString(), policy);
        }
        if (policy.constrainsCalls() && u.llmCalls() > policy.maxLlmCalls()) {
            return BudgetDecision.exceeded("llmCalls", u, String.valueOf(policy.maxLlmCalls()), policy);
        }
        if (policy.constrainsDuration() && elapsed().toMillis() > policy.maxDurationMs()) {
            return BudgetDecision.exceeded("durationMs", u, String.valueOf(policy.maxDurationMs()), policy);
        }

        double threshold = policy.warnThreshold();
        if (!warningEmitted.get()) {
            if (policy.constrainsTokens()
                    && u.totalTokens() >= policy.maxTotalTokens() * threshold) {
                return BudgetDecision.warning("tokens", u, String.valueOf(policy.maxTotalTokens()), policy);
            }
            if (policy.constrainsCost()
                    && u.costUsd().compareTo(policy.maxCostUsd()
                            .multiply(java.math.BigDecimal.valueOf(threshold))) >= 0) {
                return BudgetDecision.warning("costUsd", u, policy.maxCostUsd().toPlainString(), policy);
            }
            if (policy.constrainsCalls()
                    && u.llmCalls() >= policy.maxLlmCalls() * threshold) {
                return BudgetDecision.warning("llmCalls", u, String.valueOf(policy.maxLlmCalls()), policy);
            }
        }
        return BudgetDecision.ok(u, policy);
    }

    /**
     * Atomically claims the right to emit a one-shot warning event. Returns {@code true} exactly
     * once per ledger, so the UI shows "approaching limit" a single time instead of per token.
     */
    public boolean claimWarning() {
        return warningEmitted.compareAndSet(false, true);
    }

    /** True when a hard limit is already crossed. */
    public boolean isExceeded() {
        return evaluate().isExceeded();
    }
}
