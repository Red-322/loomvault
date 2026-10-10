package io.loomvault.store.jdbc;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.journal.JournalEntry;
import io.loomvault.core.journal.JournalEntryType;
import io.loomvault.core.journal.JournalStore;
import io.loomvault.core.json.LoomJson;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC-backed, append-only {@link JournalStore}.
 *
 * <p>Sequence allocation contract: {@code entry_seq} is strictly increasing per execution with no
 * gaps. {@link #append} computes {@code COALESCE(MAX(entry_seq), 0) + 1} and inserts under a
 * per-execution in-process lock, so concurrent appends from the same JVM never collide. When the
 * insert still fails on the primary key — the multi-instance case where another node allocated the
 * same number — the write is retried a bounded number of times with a freshly read sequence.</p>
 *
 * <p>All Spring JDBC exceptions are wrapped into {@link LoomVaultException} with a stable
 * {@link ErrorCode}.</p>
 */
public final class JdbcJournalStore implements JournalStore {

    /** Number of sequence-allocation attempts before giving up on persistent collisions. */
    private static final int MAX_ATTEMPTS = 3;

    private static final String SELECT_NEXT_SEQ =
            "SELECT COALESCE(MAX(entry_seq), 0) + 1 FROM loomvault_journal WHERE execution_id = ?";

    private static final String SELECT_LAST_SEQ =
            "SELECT COALESCE(MAX(entry_seq), 0) FROM loomvault_journal WHERE execution_id = ?";

    private static final String INSERT_ENTRY = """
            INSERT INTO loomvault_journal (execution_id, entry_seq, entry_type, node_id, payload_json, ts)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ALL = """
            SELECT execution_id, entry_seq, entry_type, node_id, payload_json, ts
              FROM loomvault_journal
             WHERE execution_id = ?
             ORDER BY entry_seq ASC
            """;

    private static final RowMapper<JournalEntry> ENTRY_MAPPER = JdbcJournalStore::mapEntry;

    private final JdbcTemplate jdbc;

    /**
     * Per-execution lock objects. Bounded remapping is not a concern here: executions are short-lived
     * and the map holds a tiny monitor per id. {@link #clearLocks()} exists for tests.
     */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public JdbcJournalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public JournalEntry append(String executionId,
                               JournalEntryType type,
                               String nodeId,
                               Map<String, Object> payload,
                               Instant timestamp) {
        // Normalise once so the persisted payload and the returned entry agree.
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        Instant safeTimestamp = timestamp == null ? Instant.now() : timestamp;
        String payloadJson = LoomJson.write(safePayload);
        long ts = safeTimestamp.toEpochMilli();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // The lock makes "read max + insert" atomic within this JVM; the PK is the last line
                // of defence across JVMs, handled by the DuplicateKeyException branch below.
                synchronized (lockFor(executionId)) {
                    long seq = nextSeq(executionId);
                    jdbc.update(INSERT_ENTRY, executionId, seq, type.name(), nodeId, payloadJson, ts);
                    return JournalEntry.of(executionId, seq, type, nodeId, safePayload, safeTimestamp);
                }
            } catch (DuplicateKeyException ex) {
                // Another instance grabbed this seq between our read and write; retry with a new one.
                if (attempt == MAX_ATTEMPTS) {
                    throw new LoomVaultException(ErrorCode.CONCURRENCY_CONFLICT,
                            "could not allocate journal sequence for execution " + executionId
                                    + " after " + MAX_ATTEMPTS + " attempts", ex);
                }
            } catch (DataAccessException ex) {
                throw new LoomVaultException(ErrorCode.PERSISTENCE_ERROR,
                        "failed to append journal entry for execution " + executionId, ex,
                        Map.of("executionId", executionId, "type", type.name()));
            }
        }
        // Unreachable: the loop either returns or throws on the final attempt.
        throw new LoomVaultException(ErrorCode.PERSISTENCE_ERROR,
                "unreachable journal append state for execution " + executionId);
    }

    @Override
    public List<JournalEntry> readAll(String executionId) {
        try {
            return jdbc.query(SELECT_ALL, ENTRY_MAPPER, executionId);
        } catch (DataAccessException ex) {
            throw new LoomVaultException(ErrorCode.PERSISTENCE_ERROR,
                    "failed to read journal for execution " + executionId, ex,
                    Map.of("executionId", executionId));
        }
    }

    @Override
    public long lastSeq(String executionId) {
        try {
            Long seq = jdbc.queryForObject(SELECT_LAST_SEQ, Long.class, executionId);
            return seq == null ? 0L : seq;
        } catch (DataAccessException ex) {
            throw new LoomVaultException(ErrorCode.PERSISTENCE_ERROR,
                    "failed to read journal tail for execution " + executionId, ex);
        }
    }

    /** Drops per-execution lock objects; intended for tests that reuse this store across cases. */
    public void clearLocks() {
        locks.clear();
    }

    // ---- Internals ---------------------------------------------------------------------------

    private long nextSeq(String executionId) {
        Long seq = jdbc.queryForObject(SELECT_NEXT_SEQ, Long.class, executionId);
        return seq == null ? 1L : seq;
    }

    private Object lockFor(String executionId) {
        return locks.computeIfAbsent(executionId, key -> new Object());
    }

    private static JournalEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new JournalEntry(
                rs.getString("execution_id"),
                rs.getLong("entry_seq"),
                JournalEntryType.valueOf(rs.getString("entry_type")),
                rs.getString("node_id"),
                LoomJson.readMap(rs.getString("payload_json")),
                Instant.ofEpochMilli(rs.getLong("ts")));
    }
}
