package io.loomvault.llm.mock;

import java.time.Duration;

/**
 * Behaviour of the offline mock provider.
 *
 * <p>The mock exists so the whole engine can be demonstrated, tested and put through CI without
 * an API key, a network connection, or a bill. Two knobs make that useful rather than merely
 * convenient:</p>
 *
 * <ul>
 *   <li><b>Simulated latency</b> — parallel branches need something to be parallel about.
 *       A tool that returns in 0 ms hides every scheduling bug.</li>
 *   <li><b>Failure injection</b> — {@code failFirstCalls} makes the first N calls throw a
 *       <em>retryable</em> error, which is how the retry/backoff path is exercised in the
 *       self-test workflow and in the integration tests.</li>
 * </ul>
 *
 * @param minLatencyMs  lower bound of the simulated latency
 * @param maxLatencyMs  upper bound of the simulated latency
 * @param failFirstCalls number of initial calls that fail with a retryable error; 0 disables it
 * @param tokensPerChar  token estimation ratio; defaults to a value that is roughly right for
 *                       mixed English/Chinese prompts
 */
public record MockOptions(
        long minLatencyMs,
        long maxLatencyMs,
        int failFirstCalls,
        double tokensPerChar
) {

    private static final MockOptions DEFAULT = new MockOptions(120L, 420L, 0, 0.34d);

    public MockOptions {
        minLatencyMs = Math.max(0L, minLatencyMs);
        maxLatencyMs = Math.max(minLatencyMs, maxLatencyMs);
        failFirstCalls = Math.max(0, failFirstCalls);
        if (tokensPerChar <= 0d || tokensPerChar > 1d) {
            tokensPerChar = 0.34d;
        }
    }

    /** Defaults: a few hundred milliseconds of latency, no injected failures. */
    public static MockOptions defaults() {
        return DEFAULT;
    }

    /** Instant responses — used by unit tests that should not sleep. */
    public static MockOptions instant() {
        return new MockOptions(0L, 0L, 0, 0.34d);
    }

    /** Fast responses with the first {@code calls} calls failing, for retry demonstrations. */
    public static MockOptions withFailures(int calls, long latencyMs) {
        return new MockOptions(latencyMs, latencyMs, calls, 0.34d);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long minLatencyMs = 120L;
        private long maxLatencyMs = 420L;
        private int failFirstCalls;
        private double tokensPerChar = 0.34d;

        public Builder latency(long min, long max) {
            this.minLatencyMs = min;
            this.maxLatencyMs = max;
            return this;
        }

        public Builder latency(Duration duration) {
            long ms = duration == null ? 0L : duration.toMillis();
            this.minLatencyMs = ms;
            this.maxLatencyMs = ms;
            return this;
        }

        public Builder failFirstCalls(int value) {
            this.failFirstCalls = value;
            return this;
        }

        public Builder tokensPerChar(double value) {
            this.tokensPerChar = value;
            return this;
        }

        public MockOptions build() {
            return new MockOptions(minLatencyMs, maxLatencyMs, failFirstCalls, tokensPerChar);
        }
    }
}
