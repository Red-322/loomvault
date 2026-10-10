package io.loomvault.llm.decorator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Classic three-state circuit breaker, hand-rolled because the semantics needed here are simpler
 * than any library's API surface.
 *
 * <pre>
 *   CLOSED ──(consecutive failures ≥ threshold)──▶ OPEN
 *     ▲                                             │
 *     │                                    (openDuration elapsed)
 *     │                                             ▼
 *     └────────(probe succeeds)──────────────── HALF_OPEN
 *                                                   │
 *                                        (probe fails) ──▶ OPEN
 * </pre>
 *
 * <p>Why this matters for an agent engine specifically: when a provider starts returning 503s,
 * every parallel branch notices at the same instant. Without a breaker, a fan-out of eight nodes
 * turns one provider outage into eight retry storms that keep the provider down. The breaker
 * fails fast, cheaply, and lets the budget guard see the aborted calls.</p>
 *
 * <p>All state transitions are guarded by the instance monitor. Calls are infrequent relative to
 * the network round-trips they gate, so the contention is a non-issue.</p>
 */
public final class CircuitBreaker {

    public enum State {
        /** Normal operation: calls pass through. */
        CLOSED,
        /** Failing fast: calls are rejected without touching the provider. */
        OPEN,
        /** Cooldown elapsed: exactly one probe call is allowed through. */
        HALF_OPEN
    }

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;
    private long tripCount;
    private long rejectedCount;

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration, Clock clock) {
        this.name = name == null ? "llm" : name;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public static CircuitBreaker defaults(String name) {
        return new CircuitBreaker(name, 5, Duration.ofSeconds(30), Clock.systemUTC());
    }

    /**
     * Asks permission to call the provider.
     *
     * @return {@code true} when the call may proceed; {@code false} means fail fast
     */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (openedAt != null && !clock.instant().isBefore(openedAt.plus(openDuration))) {
                    state = State.HALF_OPEN;
                    probeInFlight = true;
                    return true;
                }
                rejectedCount++;
                return false;
            case HALF_OPEN:
                if (probeInFlight) {
                    // Only one probe at a time: a stampede through a half-open breaker is how
                    // providers get knocked over a second time.
                    rejectedCount++;
                    return false;
                }
                probeInFlight = true;
                return true;
            default:
                return false;
        }
    }

    /** Records a successful call: resets the breaker to CLOSED. */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        probeInFlight = false;
        state = State.CLOSED;
        openedAt = null;
    }

    /** Records a failed call, possibly opening the breaker. */
    public synchronized void recordFailure() {
        probeInFlight = false;
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAt = clock.instant();
            tripCount++;
            return;
        }
        consecutiveFailures++;
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
            tripCount++;
        }
    }

    public synchronized State state() {
        // Lazily promote OPEN -> HALF_OPEN so that observers see the cooldown effect even when
        // no calls are in flight.
        if (state == State.OPEN && openedAt != null
                && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            return State.HALF_OPEN;
        }
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized long tripCount() {
        return tripCount;
    }

    public synchronized long rejectedCount() {
        return rejectedCount;
    }

    public String name() {
        return name;
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public Duration openDuration() {
        return openDuration;
    }

    /** Forces the breaker back to CLOSED. Exposed for tests and manual recovery endpoints. */
    public synchronized void reset() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        openedAt = null;
        probeInFlight = false;
    }
}
