package io.loomvault.core.journal;

import io.loomvault.core.json.LoomJson;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable record in an execution's append-only journal.
 *
 * @param executionId owning execution
 * @param seq         monotonically increasing per execution, starting at 1
 * @param type        what happened
 * @param nodeId      node the entry relates to, or {@code null} for execution-level entries
 * @param payload     structured detail; the shape depends on {@link #type}
 * @param timestamp   when the engine observed the event (not when the DB wrote it)
 */
public record JournalEntry(
        String executionId,
        long seq,
        JournalEntryType type,
        String nodeId,
        Map<String, Object> payload,
        Instant timestamp
) {

    public JournalEntry {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("journal seq must be >= 1");
        }
        if (type == null) {
            throw new IllegalArgumentException("journal entry type must not be null");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static JournalEntry of(String executionId, long seq, JournalEntryType type,
                                  String nodeId, Map<String, Object> payload, Instant timestamp) {
        return new JournalEntry(executionId, seq, type, nodeId, payload, timestamp);
    }

    /** Convenience factory for execution-level entries with no node. */
    public static JournalEntry execution(String executionId, long seq, JournalEntryType type,
                                         Map<String, Object> payload, Instant timestamp) {
        return new JournalEntry(executionId, seq, type, null, payload, timestamp);
    }

    public boolean hasNode() {
        return nodeId != null && !nodeId.isBlank();
    }

    /** Reads a payload value with a default, keeping call sites free of null gymnastics. */
    public Object payloadValue(String key, Object fallback) {
        Object value = payload.get(key);
        return value == null ? fallback : value;
    }

    public String payloadString(String key, String fallback) {
        Object value = payload.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public long payloadLong(String key, long fallback) {
        Object value = payload.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> payloadMap(String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Map.of();
    }

    /** Serialised payload, as stored in the database. */
    public String payloadJson() {
        return LoomJson.write(payload);
    }
}
