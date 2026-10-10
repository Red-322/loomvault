package io.loomvault.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Token, call and cost counters accumulated over an execution or a single node.
 *
 * <p>Immutable value object: every mutation produces a new instance, which keeps the budget
 * ledger trivially thread-safe when parallel branches finish at the same time.</p>
 *
 * @param inputTokens  prompt tokens billed by the provider
 * @param outputTokens completion tokens billed by the provider
 * @param llmCalls     number of calls that actually reached a provider
 * @param cacheHits    number of calls served from the content-addressed cache (cost: 0)
 * @param costUsd      estimated spend derived from the pricing table
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Usage(
        long inputTokens,
        long outputTokens,
        long llmCalls,
        long cacheHits,
        BigDecimal costUsd
) {

    public static final Usage ZERO = new Usage(0L, 0L, 0L, 0L, BigDecimal.ZERO);

    public Usage {
        if (costUsd == null) {
            costUsd = BigDecimal.ZERO;
        }
        if (inputTokens < 0 || outputTokens < 0 || llmCalls < 0 || cacheHits < 0) {
            throw new IllegalArgumentException("usage counters must not be negative");
        }
    }

    public static Usage of(long inputTokens, long outputTokens, long llmCalls, BigDecimal costUsd) {
        return new Usage(inputTokens, outputTokens, llmCalls, 0L, costUsd);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public Usage plus(Usage other) {
        if (other == null || other == ZERO) {
            return this;
        }
        return new Usage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                llmCalls + other.llmCalls,
                cacheHits + other.cacheHits,
                costUsd.add(other.costUsd));
    }

    public boolean isZero() {
        return inputTokens == 0L && outputTokens == 0L && llmCalls == 0L && cacheHits == 0L
                && costUsd.signum() == 0;
    }
}
