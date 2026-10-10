package io.loomvault.core.engine;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.model.NodeKind;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registry of {@link NodeHandler}s, one per {@link NodeKind}.
 *
 * <p>Registering a handler is the only step needed to teach the engine a new node capability —
 * the scheduler, the journal and the API stay untouched. That is the practical test of whether an
 * extension point is real.</p>
 */
public final class NodeHandlerRegistry {

    private final Map<NodeKind, NodeHandler> handlers;

    private NodeHandlerRegistry(Map<NodeKind, NodeHandler> handlers) {
        this.handlers = Collections.unmodifiableMap(handlers);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience: a registry with exactly the three built-in handlers wired manually. */
    public static NodeHandlerRegistry of(NodeHandler... handlers) {
        Builder builder = builder();
        for (NodeHandler handler : handlers) {
            builder.register(handler);
        }
        return builder.build();
    }

    public Optional<NodeHandler> find(NodeKind kind) {
        return Optional.ofNullable(handlers.get(kind));
    }

    /**
     * Resolves the handler for a kind.
     *
     * @throws LoomVaultException with {@link ErrorCode#NODE_NO_HANDLER} when none is registered
     */
    public NodeHandler require(NodeKind kind) {
        NodeHandler handler = handlers.get(kind);
        if (handler == null) {
            throw new LoomVaultException(ErrorCode.NODE_NO_HANDLER,
                    "no handler registered for node kind " + kind
                            + "; registered kinds: " + handlers.keySet());
        }
        return handler;
    }

    public Set<NodeKind> registeredKinds() {
        return handlers.keySet();
    }

    public static final class Builder {
        private final Map<NodeKind, NodeHandler> handlers = new EnumMap<>(NodeKind.class);

        public Builder register(NodeHandler handler) {
            if (handler == null) {
                return this;
            }
            NodeHandler existing = handlers.putIfAbsent(handler.kind(), handler);
            if (existing != null) {
                throw new IllegalStateException("a handler for " + handler.kind()
                        + " is already registered (" + existing.getClass().getName() + ")");
            }
            return this;
        }

        public NodeHandlerRegistry build() {
            if (handlers.isEmpty()) {
                throw new IllegalStateException("at least one node handler must be registered: "
                        + java.util.Arrays.stream(NodeKind.values()).map(Enum::name).collect(Collectors.joining(", ")));
            }
            return new NodeHandlerRegistry(handlers);
        }
    }
}
