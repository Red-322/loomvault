package io.loomvault.core.engine;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LoomVaultException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Startup-time registry of {@link Tool}s.
 *
 * <p>Registration is not thread-safe by design: tools are wired once during application startup,
 * and making that explicit removes an entire class of "the registry changed under a running
 * execution" bugs. Lookups are lock-free reads from a concurrent map.</p>
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** Registers a tool instance. */
    public ToolRegistry register(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("tool and its name must not be null/blank");
        }
        Tool existing = tools.putIfAbsent(tool.name(), tool);
        if (existing != null) {
            throw new IllegalStateException("a tool named '" + tool.name() + "' is already registered");
        }
        return this;
    }

    /** Registers a lambda tool. */
    public ToolRegistry register(String name, String description, boolean idempotent,
                                 BiFunction<Map<String, Object>, NodeContext, Map<String, Object>> body) {
        return register(Tool.of(name, description, idempotent, body));
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Resolves a tool or fails with a helpful message.
     *
     * @throws LoomVaultException {@link ErrorCode#NODE_NO_HANDLER} when the tool is unknown
     */
    public Tool require(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new LoomVaultException(ErrorCode.NODE_NO_HANDLER,
                    "unknown tool '" + name + "'; registered tools: " + names());
        }
        return tool;
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Set<String> names() {
        return new TreeMap<>(tools).keySet();
    }

    public Map<String, Tool> asMap() {
        return Map.copyOf(tools);
    }

    public int size() {
        return tools.size();
    }
}
