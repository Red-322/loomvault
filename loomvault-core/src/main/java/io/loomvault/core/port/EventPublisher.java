package io.loomvault.core.port;

/**
 * Fan-out point for live execution events.
 *
 * <p>The engine publishes after the journal entry is durable, never before: a subscriber must
 * never observe state that a crash would erase. Publishing is best-effort and must not throw into
 * the scheduler — a broken SSE connection cannot be allowed to fail an execution, so
 * implementations swallow transport errors and log them.</p>
 */
public interface EventPublisher {

    void publish(ExecutionEvent event);

    /** Discards everything. The default used by tests and by headless embedding. */
    EventPublisher NOOP = event -> {
    };
}
