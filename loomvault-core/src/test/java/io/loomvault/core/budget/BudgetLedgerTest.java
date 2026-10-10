package io.loomvault.core.budget;

import io.loomvault.core.model.BudgetPolicy;
import io.loomvault.core.model.BudgetStrategy;
import io.loomvault.core.model.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accumulation and evaluation semantics of {@link BudgetLedger}, plus the price table lookup
 * performed by {@link CostModel}.
 */
class BudgetLedgerTest {

    private static final Clock UTC = Clock.systemUTC();

    // -----------------------------------------------------------------------------------------
    // Accumulation
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("accumulates usage across multiple records")
    void shouldAccumulateUsage() {
        BudgetLedger ledger = BudgetLedger.unlimited("workflow", UTC);

        ledger.record(Usage.of(100, 50, 1, new BigDecimal("0.01")));
        ledger.record(Usage.of(200, 25, 2, new BigDecimal("0.02")));

        Usage snapshot = ledger.snapshot();
        assertThat(snapshot.inputTokens()).isEqualTo(300);
        assertThat(snapshot.outputTokens()).isEqualTo(75);
        assertThat(snapshot.totalTokens()).isEqualTo(375);
        assertThat(snapshot.llmCalls()).isEqualTo(3);
        assertThat(snapshot.costUsd()).isEqualByComparingTo("0.03");
    }

    @Test
    @DisplayName("ignores null and zero deltas")
    void shouldIgnoreEmptyDeltas() {
        BudgetLedger ledger = BudgetLedger.unlimited("workflow", UTC);
        ledger.record(null);
        ledger.record(Usage.ZERO);
        assertThat(ledger.snapshot().isZero()).isTrue();
    }

    // -----------------------------------------------------------------------------------------
    // evaluate(): OK / WARNING / EXCEEDED
    // -----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("evaluate()")
    class Evaluate {

        @Test
        @DisplayName("returns OK while comfortably inside the limit")
        void shouldReturnOkWithinLimit() {
            BudgetLedger ledger = new BudgetLedger("w", BudgetPolicy.ofTokens(1000, BudgetStrategy.WARN), UTC);
            ledger.record(Usage.of(100, 0, 1, BigDecimal.ZERO));

            BudgetDecision decision = ledger.evaluate();
            assertThat(decision.status()).isEqualTo(BudgetDecision.Status.OK);
            assertThat(decision.isExceeded()).isFalse();
            assertThat(decision.isWarning()).isFalse();
        }

        @Test
        @DisplayName("returns WARNING once the threshold fraction is reached")
        void shouldReturnWarningAtThreshold() {
            BudgetLedger ledger = new BudgetLedger("w", BudgetPolicy.ofTokens(1000, BudgetStrategy.WARN), UTC);
            ledger.record(Usage.of(800, 0, 1, BigDecimal.ZERO));

            BudgetDecision decision = ledger.evaluate();
            assertThat(decision.status()).isEqualTo(BudgetDecision.Status.WARNING);
            assertThat(decision.dimension()).isEqualTo("tokens");
        }

        @Test
        @DisplayName("returns EXCEEDED only when the limit is strictly crossed")
        void shouldReturnExceededPastLimit() {
            BudgetLedger ledger = new BudgetLedger("w", BudgetPolicy.ofTokens(1000, BudgetStrategy.FAIL), UTC);
            ledger.record(Usage.of(1000, 0, 1, BigDecimal.ZERO));
            assertThat(ledger.evaluate().status()).isEqualTo(BudgetDecision.Status.WARNING);

            ledger.record(Usage.of(1, 0, 0, BigDecimal.ZERO));
            BudgetDecision decision = ledger.evaluate();
            assertThat(decision.status()).isEqualTo(BudgetDecision.Status.EXCEEDED);
            assertThat(decision.isExceeded()).isTrue();
            assertThat(decision.dimension()).isEqualTo("tokens");
        }
    }

    @Test
    @DisplayName("enforces the cost dimension")
    void shouldEnforceCostDimension() {
        BudgetLedger ledger = new BudgetLedger("w",
                BudgetPolicy.ofCost(new BigDecimal("1.00"), BudgetStrategy.FAIL), UTC);
        ledger.record(Usage.of(0, 0, 1, new BigDecimal("1.01")));

        BudgetDecision decision = ledger.evaluate();
        assertThat(decision.isExceeded()).isTrue();
        assertThat(decision.dimension()).isEqualTo("costUsd");
    }

    @Test
    @DisplayName("enforces the llmCalls dimension")
    void shouldEnforceCallDimension() {
        BudgetPolicy policy = new BudgetPolicy(0L, 2L, null, 0L, BudgetStrategy.FAIL, 0.8d);
        BudgetLedger ledger = new BudgetLedger("w", policy, UTC);
        ledger.record(Usage.of(0, 0, 3, BigDecimal.ZERO));

        BudgetDecision decision = ledger.evaluate();
        assertThat(decision.isExceeded()).isTrue();
        assertThat(decision.dimension()).isEqualTo("llmCalls");
    }

    @Test
    @DisplayName("enforces the wall-clock dimension from the original start time")
    void shouldEnforceDurationDimension() {
        BudgetPolicy policy = new BudgetPolicy(0L, 0L, null, 1_000L, BudgetStrategy.FAIL, 0.8d);
        // Started five seconds ago: the elapsed wall clock already blows a one-second budget.
        BudgetLedger ledger = new BudgetLedger("w", policy, UTC, Usage.ZERO, Instant.now().minusSeconds(5));

        BudgetDecision decision = ledger.evaluate();
        assertThat(decision.isExceeded()).isTrue();
        assertThat(decision.dimension()).isEqualTo("durationMs");
    }

    // -----------------------------------------------------------------------------------------
    // Warning claim
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("claims the one-shot warning exactly once")
    void shouldClaimWarningOnce() {
        BudgetLedger ledger = new BudgetLedger("w", BudgetPolicy.ofTokens(1000, BudgetStrategy.WARN), UTC);
        ledger.record(Usage.of(800, 0, 1, BigDecimal.ZERO));
        assertThat(ledger.evaluate().isWarning()).isTrue();

        assertThat(ledger.claimWarning()).isTrue();
        assertThat(ledger.claimWarning()).isFalse();
    }

    // -----------------------------------------------------------------------------------------
    // Resuming
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("resuming() preserves replayed usage and continues accumulating")
    void shouldPreserveHistoryWhenResuming() {
        Usage replayed = Usage.of(300, 100, 2, new BigDecimal("0.05"));
        BudgetLedger ledger = BudgetLedger.resuming("w", BudgetPolicy.ofTokens(2000, BudgetStrategy.WARN), UTC,
                replayed, Instant.now().minusSeconds(30));

        assertThat(ledger.snapshot()).isEqualTo(replayed);

        ledger.record(Usage.of(50, 0, 1, BigDecimal.ZERO));
        assertThat(ledger.snapshot().totalTokens()).isEqualTo(450);
        assertThat(ledger.snapshot().llmCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("resuming() keeps the original duration clock, not a fresh one")
    void shouldNotResetDurationClockOnResume() {
        BudgetPolicy policy = new BudgetPolicy(0L, 0L, null, 1_000L, BudgetStrategy.FAIL, 0.8d);
        BudgetLedger ledger = BudgetLedger.resuming("w", policy, UTC, Usage.ZERO,
                Instant.now().minusSeconds(10));

        assertThat(ledger.evaluate().dimension()).isEqualTo("durationMs");
    }

    // -----------------------------------------------------------------------------------------
    // CostModel
    // -----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("CostModel")
    class Costs {

        private final CostModel model = CostModel.defaults();

        @Test
        @DisplayName("prices an exact model match")
        void shouldPriceExactMatch() {
            assertThat(model.estimate("gpt-4o", 1_000_000, 1_000_000))
                    .isEqualByComparingTo("12.50");
        }

        @Test
        @DisplayName("resolves a versioned model id by longest prefix")
        void shouldResolveVersionedModelByPrefix() {
            assertThat(model.lookup("gpt-4o-2024-08-06")).isPresent()
                    .get()
                    .extracting(ModelPricing::model)
                    .isEqualTo("gpt-4o");

            // Same price as the base model, not the cheaper mini variant.
            assertThat(model.estimate("gpt-4o-2024-08-06", 1_000_000, 0))
                    .isEqualByComparingTo("2.50");
            assertThat(model.isKnown("gpt-4o-2024-08-06")).isTrue();
        }

        @Test
        @DisplayName("charges nothing for an unknown model instead of inventing a price")
        void shouldReturnZeroForUnknownModel() {
            assertThat(model.estimate("totally-unknown-model", 1_000_000, 1_000_000))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(model.isKnown("totally-unknown-model")).isFalse();
            assertThat(model.lookup("totally-unknown-model")).isEmpty();
        }

        @Test
        @DisplayName("returns zero for a blank model id")
        void shouldReturnZeroForBlankModel() {
            assertThat(model.estimate("", 100, 100)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(model.lookup(null)).isEmpty();
        }
    }
}
