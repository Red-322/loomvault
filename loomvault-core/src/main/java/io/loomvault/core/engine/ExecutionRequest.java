package io.loomvault.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caller-supplied parameters for starting an execution.
 *
 * @param executionId optional client-chosen id; when blank the engine generates a sortable one.
 *                    Supplying it makes start idempotent at the caller's discretion — a retried
 *                    HTTP POST with the same id returns the existing execution instead of
 *                    starting a second one.
 * @param inputs      workflow inputs, exposed to templates as {@code ${inputs.*}}
 * @param metadata    free-form labels (operator, ticket, environment) for filtering in the UI
 */
public record ExecutionRequest(
        String executionId,
        Map<String, Object> inputs,
        Map<String, Object> metadata
) {

    public ExecutionRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ExecutionRequest of(Map<String, Object> inputs) {
        return new ExecutionRequest(null, inputs, Map.of());
    }

    public static ExecutionRequest empty() {
        return new ExecutionRequest(null, Map.of(), Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String executionId;
        private final Map<String, Object> inputs = new LinkedHashMap<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        public Builder executionId(String value) {
            this.executionId = value;
            return this;
        }

        public Builder input(String key, Object value) {
            this.inputs.put(key, value);
            return this;
        }

        public Builder inputs(Map<String, Object> value) {
            if (value != null) {
                this.inputs.putAll(value);
            }
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            if (value != null) {
                this.metadata.putAll(value);
            }
            return this;
        }

        public ExecutionRequest build() {
            return new ExecutionRequest(executionId, inputs, metadata);
        }
    }
}
