package io.loomvault.core.engine;

import java.time.Duration;

/**
 * Engine-wide tuning knobs.
 *
 * <p>Deliberately few. Everything that can be expressed per node (timeouts, retries) belongs to
 * the workflow definition where the person who understands the step can see it; this record only
 * holds the knobs that govern the engine process itself.</p>
 *
 * @param maxParallelNodes     how many nodes may execute concurrently. Bounds the load on the
 *                             model provider far more naturally than a rate limiter, because it
 *                             caps in-flight work instead of rejecting it after the fact.
 * @param defaultMaxAttempts   retries applied when a node declares none
 * @param pollInterval         how often the scheduler wakes up while waiting for completions —
 *                             this is what makes cancellation and budget checks responsive
 * @param approvalWaitWarning  how long an execution may sit in {@code WAITING_APPROVAL} before
 *                             the engine emits a reminder event
 * @param hardTimeoutSlack     extra grace added on top of {@code timeout × attempts} before the
 *                             watchdog abandons a stuck worker thread
 */
public record EngineOptions(
        int maxParallelNodes,
        int defaultMaxAttempts,
        Duration pollInterval,
        Duration approvalWaitWarning,
        Duration hardTimeoutSlack
) {

    private static final EngineOptions DEFAULTS = new EngineOptions(
            4,
            2,
            Duration.ofMillis(200),
            Duration.ofHours(24),
            Duration.ofSeconds(30));

    public EngineOptions {
        if (maxParallelNodes < 1) {
            throw new IllegalArgumentException("maxParallelNodes must be >= 1");
        }
        if (defaultMaxAttempts < 1) {
            throw new IllegalArgumentException("defaultMaxAttempts must be >= 1");
        }
        pollInterval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                ? Duration.ofMillis(200) : pollInterval;
        approvalWaitWarning = approvalWaitWarning == null ? Duration.ofHours(24) : approvalWaitWarning;
        hardTimeoutSlack = hardTimeoutSlack == null ? Duration.ofSeconds(30) : hardTimeoutSlack;
    }

    public static EngineOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxParallelNodes = 4;
        private int defaultMaxAttempts = 2;
        private Duration pollInterval = Duration.ofMillis(200);
        private Duration approvalWaitWarning = Duration.ofHours(24);
        private Duration hardTimeoutSlack = Duration.ofSeconds(30);

        public Builder maxParallelNodes(int value) {
            this.maxParallelNodes = value;
            return this;
        }

        public Builder defaultMaxAttempts(int value) {
            this.defaultMaxAttempts = value;
            return this;
        }

        public Builder pollInterval(Duration value) {
            this.pollInterval = value;
            return this;
        }

        public Builder approvalWaitWarning(Duration value) {
            this.approvalWaitWarning = value;
            return this;
        }

        public Builder hardTimeoutSlack(Duration value) {
            this.hardTimeoutSlack = value;
            return this;
        }

        public EngineOptions build() {
            return new EngineOptions(maxParallelNodes, defaultMaxAttempts, pollInterval,
                    approvalWaitWarning, hardTimeoutSlack);
        }
    }
}
