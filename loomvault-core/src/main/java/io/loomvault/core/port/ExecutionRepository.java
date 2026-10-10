package io.loomvault.core.port;

import io.loomvault.core.model.Execution;
import io.loomvault.core.model.ExecutionStatus;
import io.loomvault.core.model.NodeState;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable storage for executions and their per-node state.
 *
 * <p>Implemented by the JDBC repository in production and by an in-memory fake inside the engine
 * tests. The engine only depends on this interface, which is what keeps {@code loomvault-core}
 * free of Spring and JDBC.</p>
 *
 * <h3>Revision ownership — read this before implementing</h3>
 * <p><b>The repository owns {@code revision}; callers never increment it.</b> The contract is:</p>
 * <ul>
 *   <li>{@link #create(Execution)} inserts the row carrying whatever revision the value already
 *       has (normally {@code 0}) and returns it unchanged.</li>
 *   <li>{@link #save(Execution)} compares the <em>incoming</em> {@code revision} with the stored
 *       one, writes {@code revision + 1}, and returns the instance carrying the incremented value.
 *       A mismatch throws {@link io.loomvault.core.error.LoomVaultException} with code
 *       {@code CONCURRENCY_CONFLICT} — this is what stops two recovering instances from driving
 *       the same execution.</li>
 * </ul>
 * <p>Get this wrong in either direction — both layers bumping, or neither — and the first state
 * transition of every execution fails with a stale-revision error.</p>
 */
public interface ExecutionRepository {

    /** Persists a brand-new execution row. */
    Execution create(Execution execution);

    /** Persists an updated execution row, bumping the revision. */
    Execution save(Execution execution);

    Optional<Execution> find(String executionId);

    /** Most recent executions first, capped at {@code limit}. */
    List<Execution> findRecent(int limit);

    List<Execution> findByStatus(Collection<ExecutionStatus> statuses, int limit);

    /** Total number of executions, for the operator dashboard. */
    long count();

    long countByStatus(ExecutionStatus status);

    /** Upserts the state of one node within an execution. */
    void saveNodeState(String executionId, NodeState state);

    /** All node states of an execution keyed by node id; empty when unknown. */
    Map<String, NodeState> loadNodeStates(String executionId);
}
