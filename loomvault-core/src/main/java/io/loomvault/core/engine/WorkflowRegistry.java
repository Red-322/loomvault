package io.loomvault.core.engine;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.graph.WorkflowGraph;
import io.loomvault.core.model.WorkflowDefinition;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory catalogue of known workflows.
 *
 * <p>Definitions are validated and compiled into a {@link WorkflowGraph} at registration time, so
 * a malformed workflow is rejected when it is uploaded rather than when it is first run — the
 * difference between a 400 at deploy time and a production incident at 3am.</p>
 *
 * <p>Graphs are cached because building one is O(V+E) and executions start frequently. A
 * definition is immutable, so the cache can never go stale within a definition's lifetime.</p>
 */
public final class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, WorkflowGraph> graphs = new ConcurrentHashMap<>();

    /** Validates and registers a definition, replacing any previous version with the same id. */
    public WorkflowRegistry register(WorkflowDefinition definition) {
        WorkflowGraph graph = WorkflowGraph.of(definition);   // throws on invalid graphs
        definitions.put(definition.id(), definition);
        graphs.put(definition.id(), graph);
        return this;
    }

    /** Registers several definitions, failing fast on the first invalid one. */
    public WorkflowRegistry registerAll(List<WorkflowDefinition> batch) {
        if (batch != null) {
            batch.forEach(this::register);
        }
        return this;
    }

    public boolean contains(String workflowId) {
        return definitions.containsKey(workflowId);
    }

    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.ofNullable(definitions.get(workflowId));
    }

    public WorkflowDefinition require(String workflowId) {
        WorkflowDefinition definition = definitions.get(workflowId);
        if (definition == null) {
            throw new LoomVaultException(ErrorCode.INVALID_WORKFLOW,
                    "unknown workflow '" + workflowId + "'; registered: " + definitions.keySet());
        }
        return definition;
    }

    public WorkflowGraph graph(String workflowId) {
        WorkflowGraph graph = graphs.get(workflowId);
        if (graph == null) {
            throw new LoomVaultException(ErrorCode.INVALID_WORKFLOW,
                    "unknown workflow '" + workflowId + "'; registered: " + definitions.keySet());
        }
        return graph;
    }

    public List<WorkflowDefinition> all() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(WorkflowDefinition::id))
                .toList();
    }

    public Optional<WorkflowDefinition> unregister(String workflowId) {
        graphs.remove(workflowId);
        return Optional.ofNullable(definitions.remove(workflowId));
    }

    public int size() {
        return definitions.size();
    }
}
