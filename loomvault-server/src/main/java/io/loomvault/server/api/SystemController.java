package io.loomvault.server.api;

import io.loomvault.core.budget.CostModel;
import io.loomvault.core.budget.ModelPricing;
import io.loomvault.core.engine.ExecutionEngine;
import io.loomvault.core.engine.ToolRegistry;
import io.loomvault.core.engine.WorkflowRegistry;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.llm.ResponseCache;
import io.loomvault.core.model.ExecutionStatus;
import io.loomvault.core.port.ExecutionRepository;
import io.loomvault.llm.decorator.CircuitBreaker;
import io.loomvault.server.events.ExecutionEventBus;
import io.loomvault.server.tools.BuiltinTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only system introspection: what is installed, how it is doing, what it costs.
 *
 * <p>The dashboard's header cards all read from here. Everything returned is safe to expose —
 * model ids and pricing, never API keys; provider host, never the token.</p>
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ExecutionRepository repository;
    private final ExecutionEngine engine;
    private final WorkflowRegistry workflows;
    private final ToolRegistry tools;
    private final LlmGateway llmGateway;
    private final ResponseCache responseCache;
    private final CostModel costModel;
    private final CircuitBreaker circuitBreaker;
    private final ExecutionEventBus eventBus;
    private final BuiltinTools builtinTools;
    private final String applicationVersion;
    private final String providerMode;

    public SystemController(ExecutionRepository repository,
                            ExecutionEngine engine,
                            WorkflowRegistry workflows,
                            ToolRegistry tools,
                            LlmGateway llmGateway,
                            ResponseCache responseCache,
                            CostModel costModel,
                            CircuitBreaker circuitBreaker,
                            ExecutionEventBus eventBus,
                            BuiltinTools builtinTools,
                            @Value("${loomvault.llm.provider:mock}") String providerMode) {
        this.repository = repository;
        this.engine = engine;
        this.workflows = workflows;
        this.tools = tools;
        this.llmGateway = llmGateway;
        this.responseCache = responseCache;
        this.costModel = costModel;
        this.circuitBreaker = circuitBreaker;
        this.eventBus = eventBus;
        this.builtinTools = builtinTools;
        this.providerMode = providerMode;
        this.applicationVersion = SystemController.class.getPackage().getImplementationVersion() == null
                ? "0.1.0-dev"
                : SystemController.class.getPackage().getImplementationVersion();
    }

    /** Version, wiring, and the health of the model path. */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("application", "loomvault");
        info.put("version", applicationVersion);
        info.put("serverTime", Instant.now().toString());
        info.put("java", System.getProperty("java.version"));
        info.put("llmProvider", llmGateway.provider());
        info.put("configuredProvider", providerMode);
        info.put("circuitState", circuitBreaker.state().name());
        info.put("registeredWorkflows", workflows.all().stream()
                .map(io.loomvault.core.model.WorkflowDefinition::id).toList());
        info.put("tools", List.copyOf(tools.names()));
        info.put("sseSubscribers", eventBus.subscriberCount());
        info.put("activeExecutions", List.copyOf(engine.activeExecutionIds()));
        return info;
    }

    /** Aggregated counters for the dashboard cards. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> byStatus = new LinkedHashMap<>();
        long total = 0;
        for (ExecutionStatus status : ExecutionStatus.values()) {
            long count = repository.countByStatus(status);
            byStatus.put(status.name(), count);
            total += count;
        }
        long hits = responseCache.hitCount();
        long misses = responseCache.missCount();
        long lookups = hits + misses;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalExecutions", total);
        stats.put("byStatus", byStatus);
        stats.put("activeExecutions", engine.activeExecutionIds().size());
        stats.put("cacheHits", hits);
        stats.put("cacheMisses", misses);
        stats.put("cacheHitRate", lookups == 0 ? 0d : Math.round((double) hits / lookups * 1000d) / 1000d);
        stats.put("completedExecutions", byStatus.get(ExecutionStatus.SUCCEEDED.name()));
        stats.put("failedExecutions", byStatus.get(ExecutionStatus.FAILED.name()));
        stats.put("budgetStoppedExecutions", byStatus.get(ExecutionStatus.BUDGET_EXCEEDED.name()));
        stats.put("waitingForApproval", byStatus.get(ExecutionStatus.WAITING_APPROVAL.name()));
        stats.put("circuit", Map.of(
                "state", circuitBreaker.state().name(),
                "trips", circuitBreaker.tripCount(),
                "rejected", circuitBreaker.rejectedCount()));
        return stats;
    }

    /** Registered tools, so the UI can explain what a TOOL node may call. */
    @GetMapping("/tools")
    public List<Map<String, Object>> toolsList() {
        return tools.asMap().values().stream()
                .map(tool -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", tool.name());
                    row.put("description", tool.description());
                    row.put("idempotent", tool.idempotent());
                    return row;
                })
                .sorted(java.util.Comparator.comparing(row -> String.valueOf(row.get("name"))))
                .toList();
    }

    /** The pricing table the budget guard uses. Unknown models cost zero. */
    @GetMapping("/models")
    public Map<String, Object> models() {
        Map<String, Object> table = new LinkedHashMap<>();
        costModel.asMap().forEach((model, pricing) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("inputPerMillion", pricing.inputPerMillion());
            row.put("outputPerMillion", pricing.outputPerMillion());
            row.put("free", pricing.isFree());
            table.put(model, row);
        });
        return Map.of("models", table, "note",
                "Prices are USD per million tokens. Local models are intentionally zero-rated. "
                        + "Override with loomvault.pricing.overrides.<model>.");
    }

    /**
     * Estimated cost of a hypothetical call — handy when sizing a workflow before running it.
     */
    @GetMapping("/models/estimate")
    public Map<String, Object> estimate(String model, long inputTokens, long outputTokens) {
        ModelPricing pricing = costModel.lookup(model).orElse(null);
        BigDecimal cost = costModel.estimate(model, inputTokens, outputTokens);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        result.put("estimatedCostUsd", cost);
        result.put("known", pricing != null);
        return result;
    }

    /** Side effects produced by the demo tools, in call order. */
    @GetMapping("/effects")
    public Map<String, Object> effects() {
        return Map.of(
                "notifications", builtinTools.notifications(),
                "outcomes", builtinTools.outcomes());
    }

    /** The bundled dataset, so a reader can try the workflows with real ids. */
    @GetMapping("/sample-data")
    public Map<String, Object> sampleData() {
        return Map.of(
                "tickets", builtinTools.tickets(),
                "customers", builtinTools.customers());
    }

    /** Live view of what the scheduler is doing right now. */
    @GetMapping("/live")
    public Map<String, Object> live() {
        return Map.of(
                "active", List.copyOf(engine.activeExecutionIds()),
                "count", engine.activeExecutionIds().size(),
                "subscribers", eventBus.subscriberCount(),
                "uptimeSeconds", Duration.between(ProcessHandle.current().info().startInstant().orElse(Instant.now()),
                        Instant.now()).toSeconds());
    }
}
