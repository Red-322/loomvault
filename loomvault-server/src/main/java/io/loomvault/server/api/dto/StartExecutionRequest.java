package io.loomvault.server.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Body of {@code POST /api/executions}.
 *
 * @param workflowId  id of a registered workflow
 * @param executionId optional caller-supplied id. Supplying one makes the call idempotent: a
 *                    retried request with the same id returns the existing run instead of
 *                    starting a second one. This is what a payment-webhook-style caller needs.
 * @param inputs      workflow inputs, exposed to templates as {@code ${inputs.*}}
 * @param metadata    free-form labels for filtering (operator, environment, ticket)
 */
public record StartExecutionRequest(
        @NotBlank(message = "workflowId is required")
        String workflowId,
        String executionId,
        Map<String, Object> inputs,
        Map<String, Object> metadata
) {

    public StartExecutionRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
