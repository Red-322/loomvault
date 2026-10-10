package io.loomvault.store.memory;

import io.loomvault.core.journal.JournalEntry;
import io.loomvault.core.journal.JournalEntryType;
import io.loomvault.core.journal.JournalStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, append-only {@link JournalStore} for tests and the standalone demo profile.
 *
 * <p>Sequence allocation matches the JDBC implementation: {@code seq} starts at 1 and increases by
 * one per append, computed under a per-execution lock so concurrent appends from different threads
 * cannot observe the same number. Entries are never mutated once written; {@link #clear()} resets
 * the store between tests.</p>
 */
public final class InMemoryJournalStore implements JournalStore {

    /** Entries per execution, kept in insertion (sequence) order. */
    private final ConcurrentHashMap<String, List<JournalEntry>> journals = new ConcurrentHashMap<>();

    /** Per-execution monitors guarding the read-tail / append critical section. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public JournalEntry append(String executionId,
                               JournalEntryType type,
                               String nodeId,
                               Map<String, Object> payload,
                               Instant timestamp) {
        synchronized (lockFor(executionId)) {
            List<JournalEntry> entries = journals.computeIfAbsent(executionId,
                    key -> new CopyOnWriteArrayList<>());
            long seq = entries.isEmpty() ? 1L : entries.get(entries.size() - 1).seq() + 1;
            JournalEntry entry = JournalEntry.of(executionId, seq, type, nodeId, payload, timestamp);
            entries.add(entry);
            return entry;
        }
    }

    @Override
    public List<JournalEntry> readAll(String executionId) {
        List<JournalEntry> entries = journals.get(executionId);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    @Override
    public long lastSeq(String executionId) {
        List<JournalEntry> entries = journals.get(executionId);
        return entries == null || entries.isEmpty() ? 0L : entries.get(entries.size() - 1).seq();
    }

    /** Removes every journal; intended for test setup/teardown. */
    public void clear() {
        journals.clear();
        locks.clear();
    }

    private Object lockFor(String executionId) {
        return locks.computeIfAbsent(executionId, key -> new Object());
    }
}
