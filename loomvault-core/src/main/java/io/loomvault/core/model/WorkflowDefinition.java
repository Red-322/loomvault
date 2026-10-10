package io.loomvault.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, versioned workflow definition.
 *
 * <p>Definitions are pure data: the engine never mutates them. That is what allows a completed
 * execution to be replayed months later — the definition is archived next to its journal, so the
 * replay uses the exact graph that originally ran, not the latest one.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDefinition(
        String id,
        String name,
        String version,
        String description,
        BudgetPolicy budget,
        List<NodeDefinition> nodes,
        List<EdgeDefinition> edges,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public WorkflowDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("workflow id must not be blank");
        }
        name = (name == null || name.isBlank()) ? id : name;
        version = (version == null || version.isBlank()) ? "1" : version;
        budget = budget == null ? BudgetPolicy.UNLIMITED : budget;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public Optional<NodeDefinition> findNode(String nodeId) {
        return nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst();
    }

    public NodeDefinition requireNode(String nodeId) {
        return findNode(nodeId).orElseThrow(() ->
                new IllegalArgumentException("workflow '" + id + "' has no node '" + nodeId + "'"));
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public Builder toBuilder() {
        return new Builder()
                .id(id).name(name).version(version).description(description)
                .budget(budget).nodes(nodes).edges(edges).metadata(metadata).createdAt(createdAt);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder kept deliberately small — definitions are usually loaded from JSON/YAML. */
    public static final class Builder {
        private String id;
        private String name;
        private String version = "1";
        private String description;
        private BudgetPolicy budget = BudgetPolicy.UNLIMITED;
        private final List<NodeDefinition> nodes = new ArrayList<>();
        private final List<EdgeDefinition> edges = new ArrayList<>();
        private Map<String, Object> metadata = new LinkedHashMap<>();
        private Instant createdAt = Instant.now();

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder version(String value) {
            this.version = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder budget(BudgetPolicy value) {
            this.budget = value;
            return this;
        }

        public Builder node(NodeDefinition value) {
            this.nodes.add(value);
            return this;
        }

        public Builder nodes(List<NodeDefinition> value) {
            if (value != null) {
                this.nodes.addAll(value);
            }
            return this;
        }

        public Builder edge(EdgeDefinition value) {
            this.edges.add(value);
            return this;
        }

        public Builder edge(String from, String to) {
            return edge(EdgeDefinition.of(from, to));
        }

        public Builder edge(String from, String to, String condition) {
            return edge(EdgeDefinition.when(from, to, condition));
        }

        public Builder edges(List<EdgeDefinition> value) {
            if (value != null) {
                this.edges.addAll(value);
            }
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            if (value != null) {
                this.metadata.putAll(value);
            }
            return this;
        }

        public Builder createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        public WorkflowDefinition build() {
            return new WorkflowDefinition(id, name, version, description, budget,
                    nodes, edges, metadata, createdAt);
        }
    }
}
