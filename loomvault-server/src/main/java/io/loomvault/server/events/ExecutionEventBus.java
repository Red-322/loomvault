package io.loomvault.server.events;

import io.loomvault.core.port.EventPublisher;
import io.loomvault.core.port.ExecutionEvent;
import io.loomvault.server.observability.ExecutionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process fan-out from the engine's event stream to SSE subscribers.
 *
 * <p>Subscribers register either for one execution ({@code /api/executions/{id}/events}) or for
 * everything ({@code /api/executions/stream}). Delivery is best-effort by design: a slow or dead
 * browser must never slow down or fail an execution, so publish failures are swallowed and the
 * subscriber is dropped.</p>
 *
 * <p>Backpressure is handled by the servlet container's SSE buffer — when a client cannot keep up,
 * the connection is terminated rather than buffered without bound. A reconnecting client replays
 * from the journal, which is why the journal exists.</p>
 *
 * <p>For multi-instance deployments this class is the seam to replace with a Redis pub/sub or
 * Kafka fan-out: the engine only knows the {@link EventPublisher} interface.</p>
 */
@Component
public final class ExecutionEventBus implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEventBus.class);
    private static final String WILDCARD = "*";
    private static final Duration STREAM_TIMEOUT = Duration.ofHours(2);

    private final ExecutionMetrics metrics;
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public ExecutionEventBus(ExecutionMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void publish(ExecutionEvent event) {
        metrics.record(event);
        dispatch(event.executionId(), event);
    }

    /** Subscribes to one execution's stream. */
    public SseEmitter subscribe(String executionId) {
        return register(executionId == null ? WILDCARD : executionId);
    }

    /** Subscribes to the firehose: every event of every execution. */
    public SseEmitter subscribeAll() {
        return register(WILDCARD);
    }

    /** Number of live subscribers, surfaced on the dashboard. */
    public int subscriberCount() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    private SseEmitter register(String key) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        List<SseEmitter> list = subscribers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> current = subscribers.get(key);
            if (current != null) {
                current.remove(emitter);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(throwable -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "subscription", key,
                    "timestamp", java.time.Instant.now().toString())));
        } catch (IOException ex) {
            cleanup.run();
            emitter.completeWithError(ex);
        }
        log.debug("sse.subscribe key={} total={}", key, subscriberCount());
        return emitter;
    }

    private void dispatch(String executionId, ExecutionEvent event) {
        send(executionId, event);
        send(WILDCARD, event);
    }

    private void send(String key, ExecutionEvent event) {
        List<SseEmitter> list = subscribers.get(key);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name().toLowerCase())
                        .id(String.valueOf(event.seq()))
                        .data(event));
            } catch (Exception ex) {
                // Client went away or cannot keep up: drop it, do not disturb the engine.
                list.remove(emitter);
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // Already completed.
                }
                log.debug("sse.dropped key={} reason={}", key, ex.getClass().getSimpleName());
            }
        }
    }
}
