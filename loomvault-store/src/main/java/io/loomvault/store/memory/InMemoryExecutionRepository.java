package io.loomvault.store.memory;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.model.Execution;
import io.loomvault.core.model.ExecutionStatus;
import io.loomvault.core.model.NodeState;
import io.loomvault.core.port.ExecutionRepository;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ExecutionRepository} used by engine tests and by the standalone demo profile.
 *
 * <p>It reproduces the JDBC implementation's observable contract, including optimistic locking:
 * {@link #save(Execution)} compares the stored revision with the caller's and reports a mismatch as
 * {@link ErrorCode#CONCURRENCY_CONFLICT}, exactly like the {@code affected == 0} case does in
 * production. Nothing is durable — {@link #clear()} resets the whole store between tests.</p>
 */
public final class InMemoryExecutionRepository implements ExecutionRepository {

    private final ConcurrentHashMap<String, Execution> executions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, NodeState>> nodeStates = new ConcurrentHashMap<>();

    @Override
    public Execution create(Execution execution) {
        Execution existing = executions.putIfAbsent(execution.id(), execution);
        if (existing != null) {
            throw new LoomVaultException(ErrorCode.CONCURRENCY_CONFLICT,
                    "execution already exists: " + execution.id());
        }
        return execution;
    }

    @Override
    public Execution save(Execution execution) {
        // compute() serialises concurrent writers per key; throwing inside the lambda aborts the
        // update and propagates to the caller, mirroring a zero-row UPDATE.
        return executions.compute(execution.id(), (id, current) -> {
            if (current == null || current.revision() != execution.revision()) {
                throw new LoomVaultException(ErrorCode.CONCURRENCY_CONFLICT,
                        "stale revision for execution " + id
                                + " (expected revision " + execution.revision() + ")");
            }
            return execution.withRevision(execution.revision() + 1);
        });
    }

    @Override
    public Optional<Execution> find(String executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override
    public List<Execution> findRecent(int limit) {
        return executions.values().stream()
                .sorted(Comparator.comparing(Execution::createdAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<Execution> findByStatus(Collection<ExecutionStatus> statuses, int limit) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return executions.values().stream()
                .filter(execution -> statuses.contains(execution.status()))
                .sorted(Comparator.comparing(Execution::createdAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public long count() {
        return executions.size();
    }

    @Override
    public long countByStatus(ExecutionStatus status) {
        return executions.values().stream()
                .filter(execution -> execution.status() == status)
                .count();
    }

    @Override
    public void saveNodeState(String executionId, NodeState state) {
        nodeStates.computeIfAbsent(executionId, key -> new ConcurrentHashMap<>())
                .put(state.nodeId(), state);
    }

    @Override
    public Map<String, NodeState> loadNodeStates(String executionId) {
        Map<String, NodeState> states = nodeStates.get(executionId);
        return states == null ? Map.of() : new LinkedHashMap<>(states);
    }

    /** Removes every execution and node state; intended for test setup/teardown. */
    public void clear() {
        executions.clear();
        nodeStates.clear();
    }
}
