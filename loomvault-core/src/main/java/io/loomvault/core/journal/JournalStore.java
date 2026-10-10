package io.loomvault.core.journal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Durable, append-only event log — the source of truth for crash recovery.
 *
 * <p>Implementations must guarantee:</p>
 * <ul>
 *   <li><b>Monotonic sequence.</b> {@code seq} is strictly increasing per execution and has no
 *       gaps; {@link #append} is the only writer and allocates the number atomically.</li>
 *   <li><b>Durability before acknowledgement.</b> Once {@code append} returns, the entry survives
 *       a process crash.</li>
 *   <li><b>Immutability.</b> Entries are never modified or deleted (except when the whole
 *       execution is purged by retention policy).</li>
 * </ul>
 */
public interface JournalStore {

    /**
     * Appends one entry and returns it with its allocated sequence number.
     *
     * @param executionId owning execution
     * @param type        event type
     * @param nodeId      related node, may be {@code null}
     * @param payload     structured detail, may be {@code null}
     * @param timestamp   event time (supplied by the caller's clock for testability)
     * @return the persisted entry, including the allocated {@code seq}
     */
    JournalEntry append(String executionId,
                        JournalEntryType type,
                        String nodeId,
                        Map<String, Object> payload,
                        Instant timestamp);

    /** All entries for an execution, ordered by {@code seq} ascending. */
    List<JournalEntry> readAll(String executionId);

    /** Highest allocated sequence number, or {@code 0} when the journal is empty. */
    long lastSeq(String executionId);
}
