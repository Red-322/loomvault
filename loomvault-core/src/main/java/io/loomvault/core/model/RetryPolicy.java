package io.loomvault.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Declarative retry policy with exponential backoff and optional jitter.
 *
 * <p>Backoff is computed from the <em>previous</em> attempt number so that
 * {@code backoffFor(1)} is the delay before attempt #2.</p>
 *
 * @param maxAttempts    total attempts including the first one; 1 means "no retry"
 * @param initialBackoff delay before the second attempt
 * @param multiplier     growth factor applied per additional attempt
 * @param jitterRatio    randomisation in {@code [0,1)}: the actual sleep is uniformly drawn from
 *                       {@code [delay * (1 - ratio), delay * (1 + ratio)]}. Jitter prevents
 *                       synchronised retry storms against a recovering provider.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        double multiplier,
        double jitterRatio
) {

    public static final RetryPolicy NO_RETRY = new RetryPolicy(1, Duration.ZERO, 1.0d, 0.0d);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1 (1 means no retry)");
        }
        if (initialBackoff == null) {
            initialBackoff = Duration.ZERO;
        }
        if (initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must not be negative");
        }
        if (multiplier < 1.0d) {
            multiplier = 1.0d;
        }
        jitterRatio = Math.clamp(jitterRatio, 0.0d, 1.0d);
    }

    public static RetryPolicy none() {
        return NO_RETRY;
    }

    /** Fixed delay between attempts. */
    public static RetryPolicy fixed(int attempts, Duration delay) {
        return new RetryPolicy(attempts, delay, 1.0d, 0.0d);
    }

    /** Classic exponential backoff, e.g. {@code exponential(4, 200ms, 2.0)}. */
    public static RetryPolicy exponential(int attempts, Duration initial, double multiplier) {
        return new RetryPolicy(attempts, initial, multiplier, 0.1d);
    }

    public boolean retriesEnabled() {
        return maxAttempts > 1;
    }

    /** Maximum number of retries after the initial attempt. */
    public int maxRetries() {
        return maxAttempts - 1;
    }

    /**
     * Delay to apply after {@code attempt} failed.
     *
     * @param attempt 1-based number of the attempt that just failed
     */
    public Duration backoffFor(int attempt) {
        if (attempt < 1 || initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        double grown = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1);
        double jittered = jitterRatio <= 0d
                ? grown
                : grown * (1.0d - jitterRatio + 2.0d * jitterRatio * ThreadLocalRandom.current().nextDouble());
        // Guard against overflow on absurdly long chains of retries.
        long millis = (long) Math.min(jittered, (double) Duration.ofHours(1).toMillis());
        return Duration.ofMillis(Math.max(0L, millis));
    }
}
