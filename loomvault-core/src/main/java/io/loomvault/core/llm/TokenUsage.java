package io.loomvault.core.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Token accounting for one provider call.
 *
 * @param inputTokens  prompt tokens
 * @param outputTokens completion tokens
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenUsage(long inputTokens, long outputTokens) {

    public static final TokenUsage EMPTY = new TokenUsage(0L, 0L);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public static TokenUsage of(long input, long output) {
        return new TokenUsage(input, output);
    }

    public long total() {
        return inputTokens + outputTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
