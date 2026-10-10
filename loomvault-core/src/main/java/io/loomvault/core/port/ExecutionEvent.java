package io.loomvault.core.port;

import io.loomvault.core.journal.JournalEntry;
import io.loomvault.core.journal.JournalEntryType;

import java.time.Instant;
import java.util.Map;

/**
 * A journal entry enriched with the context the UI needs to render it live.
 *
 * <p>Journal entries are the durable record; events are the same information shaped for
 * streaming. Keeping them separate means the SSE endpoint can evolve (add fields, reorder)
 * without touching the storage format that replay depends on.</p>
 */
public record ExecutionEvent(
        String executionId,
        String workflowId,
        long seq,
        JournalEntryType type,
        String nodeId,
        Map<String, Object> data,
        Instant timestamp
) {

    public ExecutionEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    /** Adapts a journal entry into a streamable event. */
    public static ExecutionEvent from(JournalEntry entry, String workflowId) {
        return new ExecutionEvent(entry.executionId(), workflowId, entry.seq(), entry.type(),
                entry.nodeId(), entry.payload(), entry.timestamp());
    }

    /** Stable stream key so subscribers can filter by execution. */
    public String topic() {
        return "execution:" + executionId;
    }
}
