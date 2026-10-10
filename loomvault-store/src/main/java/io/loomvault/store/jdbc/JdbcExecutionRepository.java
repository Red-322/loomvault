package io.loomvault.store.jdbc;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.json.LoomJson;
import io.loomvault.core.model.Execution;
import io.loomvault.core.model.ExecutionStatus;
import io.loomvault.core.model.NodeState;
import io.loomvault.core.model.NodeStatus;
import io.loomvault.core.model.Usage;
import io.loomvault.core.port.ExecutionRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC-backed {@link ExecutionRepository} for production deployments.
 *
 * <p>Durability contract:</p>
 * <ul>
 *   <li>{@link #create(Execution)} inserts a brand-new row; a primary-key collision is reported
 *       as {@link ErrorCode#CONCURRENCY_CONFLICT} so two engine instances racing to create the same
 *       execution cannot both win.</li>
 *   <li>{@link #save(Execution)} applies an optimistic lock
 *       ({@code UPDATE ... WHERE id = ? AND revision = ?}). When another writer got there first the
 *       affected-row count is {@code 0}, which is surfaced as {@link ErrorCode#CONCURRENCY_CONFLICT}.
 *       On success the returned {@link Execution} carries an incremented {@code revision}.</li>
 *   <li>{@link #saveNodeState(String, NodeState)} is a portable upsert implemented as
 *       "UPDATE first, INSERT when nothing matched", avoiding database-specific MERGE/ON CONFLICT.</li>
 * </ul>
 *
 * <p>Every {@link DataAccessException} is wrapped into a {@link LoomVaultException} carrying a
 * stable {@link ErrorCode}; callers never see a raw Spring JDBC exception.</p>
 */
public final class JdbcExecutionRepository implements ExecutionRepository {

    // ---- SQL ---------------------------------------------------------------------------------

    private static final String INSERT_EXECUTION = """
            INSERT INTO loomvault_execution
                (id, workflow_id, workflow_version, status, inputs_json, outputs_json, usage_json,
                 failure_code, failure_reason, created_at, started_at, finished_at,
                 resume_count, revision, metadata_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // Optimistic lock: the WHERE clause pins the row to the revision the caller read.
    private static final String UPDATE_EXECUTION = """
            UPDATE loomvault_execution
               SET workflow_id = ?, workflow_version = ?, status = ?, inputs_json = ?,
                   outputs_json = ?, usage_json = ?, failure_code = ?, failure_reason = ?,
                   created_at = ?, started_at = ?, finished_at = ?, resume_count = ?,
                   revision = ?, metadata_json = ?
             WHERE id = ? AND revision = ?
            """;

    private static final String SELECT_EXECUTION_COLUMNS = """
            SELECT id, workflow_id, workflow_version, status, inputs_json, outputs_json, usage_json,
                   failure_code, failure_reason, created_at, started_at, finished_at,
                   resume_count, revision, metadata_json
              FROM loomvault_execution
            """;

    private static final String SELECT_BY_ID = SELECT_EXECUTION_COLUMNS + " WHERE id = ?";

    private static final String SELECT_RECENT =
            SELECT_EXECUTION_COLUMNS + " ORDER BY created_at DESC LIMIT ?";

    private static final String COUNT_ALL = "SELECT COUNT(*) FROM loomvault_execution";

    private static final String COUNT_BY_STATUS =
            "SELECT COUNT(*) FROM loomvault_execution WHERE status = ?";

    private static final String UPDATE_NODE_STATE = """
            UPDATE loomvault_node_state
               SET status = ?, attempt = ?, output_json = ?, error_code = ?, error_message = ?,
                   started_at = ?, finished_at = ?, usage_json = ?
             WHERE execution_id = ? AND node_id = ?
            """;

    private static final String INSERT_NODE_STATE = """
            INSERT INTO loomvault_node_state
                (execution_id, node_id, status, attempt, output_json, error_code, error_message,
                 started_at, finished_at, usage_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_NODE_STATES = """
            SELECT node_id, status, attempt, output_json, error_code, error_message,
                   started_at, finished_at, usage_json
              FROM loomvault_node_state
             WHERE execution_id = ?
            """;

    /** Reusable row mapper; stateless so it is safe to share across threads. */
    private static final RowMapper<Execution> EXECUTION_MAPPER = JdbcExecutionRepository::mapExecution;

    private static final RowMapper<NodeState> NODE_STATE_MAPPER = JdbcExecutionRepository::mapNodeState;

    private final JdbcTemplate jdbc;

    public JdbcExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Execution create(Execution execution) {
        try {
            jdbc.update(INSERT_EXECUTION,
                    execution.id(),
                    execution.workflowId(),
                    execution.workflowVersion(),
                    execution.status().name(),
                    LoomJson.write(execution.inputs()),
                    LoomJson.write(execution.outputs()),
                    LoomJson.write(execution.usage()),
                    execution.failureCode(),
                    execution.failureReason(),
                    execution.createdAt().toEpochMilli(),
                    millisOrNull(execution.startedAt()),
                    millisOrNull(execution.finishedAt()),
                    execution.resumeCount(),
                    execution.revision(),
                    LoomJson.write(execution.metadata()));
            return execution;
        } catch (DuplicateKeyException ex) {
            // Someone else inserted this id first: treat exactly like a lost optimistic race.
            throw new LoomVaultException(ErrorCode.CONCURRENCY_CONFLICT,
                    "execution already exists: " + execution.id(), ex);
        } catch (DataAccessException ex) {
            throw persistenceError("create execution " + execution.id(), ex);
        }
    }

    @Override
    public Execution save(Execution execution) {
        try {
            int affected = jdbc.update(UPDATE_EXECUTION,
                    execution.workflowId(),
                    execution.workflowVersion(),
                    execution.status().name(),
                    LoomJson.write(execution.inputs()),
                    LoomJson.write(execution.outputs()),
                    LoomJson.write(execution.usage()),
                    execution.failureCode(),
                    execution.failureReason(),
                    execution.createdAt().toEpochMilli(),
                    millisOrNull(execution.startedAt()),
                    millisOrNull(execution.finishedAt()),
                    execution.resumeCount(),
                    // Write the INCREMENTED revision: the WHERE clause pinned the old value, so
                    // the row must move forward. Returning revision+1 while storing the old value
                    // is the classic way to make the second save of every execution fail.
                    execution.revision() + 1,
                    LoomJson.write(execution.metadata()),
                    execution.id(),
                    execution.revision());
            if (affected == 0) {
                throw new LoomVaultException(ErrorCode.CONCURRENCY_CONFLICT,
                        "stale revision for execution " + execution.id()
                                + " (expected revision " + execution.revision() + ")");
            }
            // The row now holds revision + 1; hand the caller the fresh value.
            return execution.withRevision(execution.revision() + 1);
        } catch (DataAccessException ex) {
            throw persistenceError("save execution " + execution.id(), ex);
        }
    }

    @Override
    public Optional<Execution> find(String executionId) {
        try {
            List<Execution> rows = jdbc.query(SELECT_BY_ID, EXECUTION_MAPPER, executionId);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        } catch (DataAccessException ex) {
            throw persistenceError("find execution " + executionId, ex);
        }
    }

    @Override
    public List<Execution> findRecent(int limit) {
        try {
            return jdbc.query(SELECT_RECENT, EXECUTION_MAPPER, limit);
        } catch (DataAccessException ex) {
            throw persistenceError("find recent executions", ex);
        }
    }

    @Override
    public List<Execution> findByStatus(Collection<ExecutionStatus> statuses, int limit) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        // Build "status IN (?, ?, ...)" with as many placeholders as there are statuses.
        String placeholders = String.join(", ", statuses.stream().map(s -> "?").toList());
        String sql = SELECT_EXECUTION_COLUMNS
                + " WHERE status IN (" + placeholders + ") ORDER BY created_at DESC LIMIT ?";
        Object[] args = new Object[statuses.size() + 1];
        int i = 0;
        for (ExecutionStatus status : statuses) {
            args[i++] = status.name();
        }
        args[i] = limit;
        try {
            return jdbc.query(sql, EXECUTION_MAPPER, args);
        } catch (DataAccessException ex) {
            throw persistenceError("find executions by status", ex);
        }
    }

    @Override
    public long count() {
        try {
            Long total = jdbc.queryForObject(COUNT_ALL, Long.class);
            return total == null ? 0L : total;
        } catch (DataAccessException ex) {
            throw persistenceError("count executions", ex);
        }
    }

    @Override
    public long countByStatus(ExecutionStatus status) {
        try {
            Long total = jdbc.queryForObject(COUNT_BY_STATUS, Long.class, status.name());
            return total == null ? 0L : total;
        } catch (DataAccessException ex) {
            throw persistenceError("count executions by status", ex);
        }
    }

    @Override
    public void saveNodeState(String executionId, NodeState state) {
        try {
            int affected = jdbc.update(UPDATE_NODE_STATE,
                    state.status().name(),
                    state.attempt(),
                    LoomJson.write(state.output()),
                    state.errorCode(),
                    state.errorMessage(),
                    millisOrNull(state.startedAt()),
                    millisOrNull(state.finishedAt()),
                    LoomJson.write(state.usage()),
                    executionId,
                    state.nodeId());
            if (affected == 0) {
                // Portable upsert: the row did not exist, so insert it now.
                jdbc.update(INSERT_NODE_STATE,
                        executionId,
                        state.nodeId(),
                        state.status().name(),
                        state.attempt(),
                        LoomJson.write(state.output()),
                        state.errorCode(),
                        state.errorMessage(),
                        millisOrNull(state.startedAt()),
                        millisOrNull(state.finishedAt()),
                        LoomJson.write(state.usage()));
            }
        } catch (DataAccessException ex) {
            throw persistenceError("save node state " + executionId + "/" + state.nodeId(), ex);
        }
    }

    @Override
    public Map<String, NodeState> loadNodeStates(String executionId) {
        try {
            List<NodeState> rows = jdbc.query(SELECT_NODE_STATES, NODE_STATE_MAPPER, executionId);
            Map<String, NodeState> byNode = new LinkedHashMap<>();
            for (NodeState state : rows) {
                byNode.put(state.nodeId(), state);
            }
            return byNode;
        } catch (DataAccessException ex) {
            throw persistenceError("load node states for " + executionId, ex);
        }
    }

    // ---- Row mapping -------------------------------------------------------------------------

    private static Execution mapExecution(ResultSet rs, int rowNum) throws SQLException {
        return new Execution(
                rs.getString("id"),
                rs.getString("workflow_id"),
                rs.getString("workflow_version"),
                ExecutionStatus.valueOf(rs.getString("status")),
                LoomJson.readMap(rs.getString("inputs_json")),
                LoomJson.readMap(rs.getString("outputs_json")),
                readUsage(rs.getString("usage_json")),
                rs.getString("failure_code"),
                rs.getString("failure_reason"),
                // created_at is NOT NULL; the other two timestamps are nullable.
                Instant.ofEpochMilli(rs.getLong("created_at")),
                instantOrNull(rs.getObject("started_at", Long.class)),
                instantOrNull(rs.getObject("finished_at", Long.class)),
                rs.getInt("resume_count"),
                rs.getLong("revision"),
                LoomJson.readMap(rs.getString("metadata_json")));
    }

    private static NodeState mapNodeState(ResultSet rs, int rowNum) throws SQLException {
        return new NodeState(
                rs.getString("node_id"),
                NodeStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt"),
                LoomJson.readMap(rs.getString("output_json")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                instantOrNull(rs.getObject("started_at", Long.class)),
                instantOrNull(rs.getObject("finished_at", Long.class)),
                readUsage(rs.getString("usage_json")));
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private static Usage readUsage(String json) {
        return json == null || json.isBlank() ? Usage.ZERO : LoomJson.read(json, Usage.class);
    }

    private static Long millisOrNull(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instantOrNull(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    private static LoomVaultException persistenceError(String action, DataAccessException cause) {
        return new LoomVaultException(ErrorCode.PERSISTENCE_ERROR,
                "failed to " + action, cause,
                Map.of("action", action));
    }
}
