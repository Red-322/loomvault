package io.loomvault.server.observability;

import io.loomvault.core.journal.JournalEntryType;
import io.loomvault.core.port.ExecutionEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translates the engine's event stream into Micrometer meters.
 *
 * <p>Metrics are derived from events rather than sprinkled through the engine. That keeps a single
 * authoritative source ("the journal knows everything") and means the metric set can change
 * without touching the scheduler — the same argument that makes the journal the source of truth
 * for recovery.</p>
 *
 * <p>Exposed at {@code /actuator/prometheus}; the metric names below are the contract the bundled
 * Grafana panel queries.</p>
 * <ul>
 *   <li>{@code loomvault_executions_started_total}</li>
 *   <li>{@code loomvault_executions_finished_total{status}}</li>
 *   <li>{@code loomvault_nodes_settled_total{result}}</li>
 *   <li>{@code loomvault_llm_tokens_total{kind}} — {@code kind} is input/output</li>
 *   <li>{@code loomvault_llm_cache_total{result}} — hit/miss</li>
 *   <li>{@code loomvault_llm_cost_usd_total}</li>
 *   <li>{@code loomvault_budget_events_total{type}} — warning/exceeded</li>
 *   <li>{@code loomvault_retries_total}</li>
 * </ul>
 */
@Component
public final class ExecutionMetrics {

    private final MeterRegistry registry;

    /** Per-execution token totals kept for the dashboard's "most expensive runs" panel. */
    private final Map<String, AtomicLong> perExecutionCost = new ConcurrentHashMap<>();

    public ExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Called by the event bus for every published event. Never throws. */
    public void record(ExecutionEvent event) {
        try {
            switch (event.type()) {
                case EXECUTION_STARTED -> registry.counter("loomvault.executions.started").increment();
                case EXECUTION_FINISHED -> {
                    registry.counter("loomvault.executions.finished",
                            "status", statusOf(event, "SUCCEEDED")).increment();
                    recordUsage(event);
                }
                case EXECUTION_STOPPED -> {
                    registry.counter("loomvault.executions.finished",
                            "status", statusOf(event, "STOPPED")).increment();
                    recordUsage(event);
                }
                case NODE_COMPLETED -> {
                    registry.counter("loomvault.nodes.settled", "result", "completed").increment();
                    recordNodeUsage(event);
                }
                case NODE_FAILED -> registry.counter("loomvault.nodes.settled", "result", "failed").increment();
                case NODE_SKIPPED -> registry.counter("loomvault.nodes.settled", "result", "skipped").increment();
                case NODE_RETRY_SCHEDULED -> registry.counter("loomvault.retries").increment();
                case CACHE_HIT -> registry.counter("loomvault.llm.cache", "result", "hit").increment();
                case BUDGET_WARNING -> registry.counter("loomvault.budget.events", "type", "warning").increment();
                case BUDGET_EXCEEDED -> registry.counter("loomvault.budget.events", "type", "exceeded").increment();
                default -> {
                    // Approval and recovery events are interesting in the journal, not in metrics.
                }
            }
        } catch (RuntimeException ignored) {
            // Observability must never break the thing it observes.
        }
    }

    private void recordUsage(ExecutionEvent event) {
        Map<String, Object> usage = asMap(event.data().get("usage"));
        if (usage.isEmpty()) {
            return;
        }
        registry.counter("loomvault.llm.tokens", "kind", "input").increment(asDouble(usage.get("inputTokens")));
        registry.counter("loomvault.llm.tokens", "kind", "output").increment(asDouble(usage.get("outputTokens")));
        double cost = asDouble(usage.get("costUsd"));
        if (cost > 0d) {
            registry.counter("loomvault.llm.cost.usd").increment(cost);
            perExecutionCost.computeIfAbsent(event.executionId(), id -> new AtomicLong())
                    .addAndGet((long) (cost * 1_000_000L));
        }
    }

    private void recordNodeUsage(ExecutionEvent event) {
        Map<String, Object> usage = asMap(event.data().get("usage"));
        if (usage.isEmpty()) {
            return;
        }
        registry.counter("loomvault.llm.tokens", "kind", "input").increment(asDouble(usage.get("inputTokens")));
        registry.counter("loomvault.llm.tokens", "kind", "output").increment(asDouble(usage.get("outputTokens")));
        if (asDouble(usage.get("cacheHits")) > 0d) {
            registry.counter("loomvault.llm.cache", "result", "hit").increment();
        } else if (asDouble(usage.get("llmCalls")) > 0d) {
            registry.counter("loomvault.llm.cache", "result", "miss").increment();
        }
    }

    private String statusOf(ExecutionEvent event, String fallback) {
        Object status = event.data().get("status");
        return status == null ? fallback : String.valueOf(status);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0d;
            }
        }
        return 0d;
    }

    /** Micro-USD spent by one execution, or {@code -1} when unknown. */
    public long executionCostMicros(String executionId) {
        AtomicLong value = perExecutionCost.get(executionId);
        return value == null ? -1L : value.get();
    }

    /** Drops per-execution bookkeeping for a purged run. */
    public void forget(String executionId) {
        perExecutionCost.remove(executionId);
    }
}
