package io.loomvault.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single run of a workflow definition.
 *
 * <p>The execution row is the small, frequently-updated "head" of the state; the detailed history
 * lives in the append-only journal and in the per-node state rows. {@code revision} is bumped on
 * every save and used for optimistic locking, so two instances recovering the same execution
 * cannot silently double-drive it.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Execution(
        String id,
        String workflowId,
        String workflowVersion,
        ExecutionStatus status,
        Map<String, Object> inputs,
        Map<String, Object> outputs,
        Usage usage,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        int resumeCount,
        long revision,
        Map<String, Object> metadata
) {

    public Execution {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("execution id must not be blank");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        workflowVersion = (workflowVersion == null || workflowVersion.isBlank()) ? "1" : workflowVersion;
        status = status == null ? ExecutionStatus.PENDING : status;
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        usage = usage == null ? Usage.ZERO : usage;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Execution pending(String id, String workflowId, String workflowVersion,
                                    Map<String, Object> inputs, Instant at) {
        return new Execution(id, workflowId, workflowVersion, ExecutionStatus.PENDING,
                inputs, Map.of(), Usage.ZERO, null, null, at, null, null, 0, 0L, Map.of());
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public long elapsedMillis(Instant now) {
        if (startedAt == null) {
            return 0L;
        }
        Instant end = finishedAt != null ? finishedAt : now;
        return Math.max(0L, end.toEpochMilli() - startedAt.toEpochMilli());
    }

    // ---- Withers ---------------------------------------------------------------------------

    public Execution withStatus(ExecutionStatus value) {
        return new Execution(id, workflowId, workflowVersion, value, inputs, outputs, usage,
                failureCode, failureReason, createdAt, startedAt, finishedAt, resumeCount, revision, metadata);
    }

    public Execution withStartedAt(Instant value) {
        return new Execution(id, workflowId, workflowVersion, status, inputs, outputs, usage,
                failureCode, failureReason, createdAt, value, finishedAt, resumeCount, revision, metadata);
    }

    public Execution withFinishedAt(Instant value) {
        return new Execution(id, workflowId, workflowVersion, status, inputs, outputs, usage,
                failureCode, failureReason, createdAt, startedAt, value, resumeCount, revision, metadata);
    }

    public Execution withUsage(Usage value) {
        return new Execution(id, workflowId, workflowVersion, status, inputs, outputs, value,
                failureCode, failureReason, createdAt, startedAt, finishedAt, resumeCount, revision, metadata);
    }

    public Execution withOutputs(Map<String, Object> value) {
        return new Execution(id, workflowId, workflowVersion, status, inputs,
                value == null ? Map.of() : Map.copyOf(value), usage,
                failureCode, failureReason, createdAt, startedAt, finishedAt, resumeCount, revision, metadata);
    }

    public Execution withFailure(String code, String reason) {
        return new Execution(id, workflowId, workflowVersion, status, inputs, outputs, usage,
                code, reason, createdAt, startedAt, finishedAt, resumeCount, revision, metadata);
    }

    public Execution withResumeCount(int value) {
        return new Execution(id, workflowId, workflowVersion, status, inputs, outputs, usage,
                failureCode, failureReason, createdAt, startedAt, finishedAt, value, revision, metadata);
    }

    public Execution withRevision(long value) {
        return new Execution(id, workflowId, workflowVersion, status, inputs, outputs, usage,
                failureCode, failureReason, createdAt, startedAt, finishedAt, resumeCount, value, metadata);
    }

    /** Adds a metadata key, preserving the rest. Used for diagnostics such as recovery markers. */
    public Execution withMetadata(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.put(key, value);
        return new Execution(id, workflowId, workflowVersion, status, inputs, outputs, usage,
                failureCode, failureReason, createdAt, startedAt, finishedAt, resumeCount, revision, copy);
    }
}
