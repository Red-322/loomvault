package io.loomvault.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declaration of a single step inside a workflow.
 *
 * <p>The {@code config} map is intentionally untyped: each {@code NodeHandler} reads the keys it
 * understands. This keeps the definition format open for extension without touching the engine —
 * a new node capability is a new handler, not a schema change.</p>
 *
 * <h3>Well-known config keys</h3>
 * <ul>
 *   <li><b>LLM</b>: {@code model}, {@code prompt} (template), {@code system}, {@code temperature},
 *       {@code maxTokens}, {@code fallbackModel} (used by the DEGRADE budget strategy),
 *       {@code outputFormat} ({@code text} or {@code json})</li>
 *   <li><b>TOOL</b>: {@code tool} (registered tool name), {@code args} (map of templated
 *       arguments)</li>
 *   <li><b>APPROVAL</b>: {@code summary} (what the human sees), {@code details}, {@code assignee}</li>
 * </ul>
 *
 * @param id         unique within the workflow
 * @param kind       what kind of handler executes this node
 * @param config     handler-specific configuration
 * @param retry      retry policy for transient failures; {@code null} means "not declared", and
 *                   the engine substitutes its configured default. Declaring
 *                   {@code maxAttempts: 1} is a deliberate "never retry" and is honoured as such.
 * @param timeout    wall-clock limit for a single attempt
 * @param budget     optional node-scoped budget
 * @param optional   when true, a failure is recorded but does not abort the whole execution
 * @param idempotent when true, the node may be re-executed during a cold resume without
 *                   side-effect risk. Nodes with {@code idempotent=false} that were interrupted
 *                   mid-flight are flagged for human review instead of being silently retried.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeDefinition(
        String id,
        String name,
        NodeKind kind,
        Map<String, Object> config,
        RetryPolicy retry,
        Duration timeout,
        BudgetPolicy budget,
        boolean optional,
        boolean idempotent
) {

    /** Default per-attempt timeout when the definition does not set one. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public NodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("node kind must not be null for node '" + id + "'");
        }
        config = config == null ? Map.of() : Map.copyOf(config);
        // `retry` is intentionally left null when undeclared: the engine substitutes its default
        // there, while an explicit `maxAttempts: 1` remains a deliberate "never retry".
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive for node '" + id + "'");
        }
        name = (name == null || name.isBlank()) ? id : name;
    }

    // ---------------------------------------------------------------------------------------
    // Factories for code-first definitions (tests and examples stay readable).
    // ---------------------------------------------------------------------------------------

    public static NodeDefinition llm(String id, String model, String prompt) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("model", model);
        cfg.put("prompt", prompt);
        return new NodeDefinition(id, id, NodeKind.LLM, cfg, RetryPolicy.NO_RETRY,
                DEFAULT_TIMEOUT, null, false, true);
    }

    public static NodeDefinition tool(String id, String toolName, Map<String, Object> args) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("tool", toolName);
        cfg.put("args", args == null ? Map.of() : args);
        return new NodeDefinition(id, id, NodeKind.TOOL, cfg, RetryPolicy.NO_RETRY,
                DEFAULT_TIMEOUT, null, false, true);
    }

    public static NodeDefinition approval(String id, String summary) {
        return new NodeDefinition(id, id, NodeKind.APPROVAL, Map.of("summary", summary),
                RetryPolicy.NO_RETRY, Duration.ofDays(30), null, false, true);
    }

    // ---------------------------------------------------------------------------------------
    // Typed config accessors with defaults.
    // ---------------------------------------------------------------------------------------

    public String stringConfig(String key, String fallback) {
        Object raw = config.get(key);
        return raw == null ? fallback : String.valueOf(raw);
    }

    public String requiredStringConfig(String key) {
        Object raw = config.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalStateException(
                    "node '" + id + "' (" + kind + ") requires config key '" + key + "'");
        }
        return String.valueOf(raw);
    }

    public int intConfig(String key, int fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        return raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
    }

    public double doubleConfig(String key, double fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        return raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
    }

    public boolean boolConfig(String key, boolean fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        return raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> mapConfig(String key) {
        Object raw = config.get(key);
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    public BigDecimal decimalConfig(String key, BigDecimal fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        return raw instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(raw).trim());
    }

    /** Effective budget: a node-level policy overrides nothing — it is enforced independently. */
    public BudgetPolicy effectiveBudget() {
        return budget == null ? BudgetPolicy.UNLIMITED : budget;
    }

    /**
     * Resolves the retry policy to execute with.
     *
     * @param defaultMaxAttempts used only when the node declared no policy at all
     */
    public RetryPolicy effectiveRetry(int defaultMaxAttempts) {
        if (retry != null) {
            return retry;
        }
        return new RetryPolicy(Math.max(1, defaultMaxAttempts), Duration.ofMillis(200), 1.0d, 0.0d);
    }

    public NodeDefinition withRetry(RetryPolicy policy) {
        return new NodeDefinition(id, name, kind, config, policy, timeout, budget, optional, idempotent);
    }

    public NodeDefinition withTimeout(Duration value) {
        return new NodeDefinition(id, name, kind, config, retry, value, budget, optional, idempotent);
    }

    public NodeDefinition withConfig(Map<String, Object> value) {
        return new NodeDefinition(id, name, kind, value, retry, timeout, budget, optional, idempotent);
    }
}
