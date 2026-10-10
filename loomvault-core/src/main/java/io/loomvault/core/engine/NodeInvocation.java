package io.loomvault.core.engine;

import io.loomvault.core.model.NodeKind;

import java.time.Instant;
import java.util.Map;

/**
 * One attempt at executing one node.
 *
 * @param executionId owning execution
 * @param nodeId      node being executed
 * @param kind        node kind, so a handler can assert it was wired correctly
 * @param config      raw node configuration (templates not yet rendered)
 * @param attempt     1-based attempt counter
 * @param startedAt   when this attempt began, per the engine clock
 * @param context     snapshot of everything the node may read
 */
public record NodeInvocation(
        String executionId,
        String nodeId,
        NodeKind kind,
        Map<String, Object> config,
        int attempt,
        Instant startedAt,
        NodeContext context
) {

    public NodeInvocation {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    // ---- Typed config accessors (same semantics as NodeDefinition) -----------------------------

    public String configString(String key, String fallback) {
        Object raw = config.get(key);
        return raw == null ? fallback : String.valueOf(raw);
    }

    public String requiredConfig(String key) {
        Object raw = config.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalStateException(
                    "node '" + nodeId + "' (" + kind + ") requires config key '" + key + "'");
        }
        return String.valueOf(raw);
    }

    public int configInt(String key, int fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        return raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
    }

    public double configDouble(String key, double fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        return raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
    }

    public boolean configBool(String key, boolean fallback) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        return raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> configMap(String key) {
        Object raw = config.get(key);
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /** Renders a template from the node config, or returns the fallback when the key is absent. */
    public String renderedOr(String key, String fallback) {
        String raw = configString(key, null);
        if (raw == null) {
            return fallback;
        }
        return context.render(raw);
    }
}
