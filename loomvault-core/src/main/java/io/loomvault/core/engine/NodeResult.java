package io.loomvault.core.engine;

import io.loomvault.core.model.Usage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link NodeHandler} returns.
 *
 * <p>Two outcomes exist, and the distinction is what makes human-in-the-loop workflows natural:
 * a handler either finished (with output and usage), or it is asking the engine to park the whole
 * execution until a human decides. The engine — not the handler — owns persistence, so a handler
 * never touches the journal.</p>
 *
 * @param output          node output; always a JSON object
 * @param usage           tokens and cost consumed by this node (empty for deterministic tools)
 * @param outcome         whether the node completed or asked for approval
 * @param approvalRequest detail shown to the approver; only set for {@link Outcome#AWAIT_APPROVAL}
 */
public record NodeResult(
        Map<String, Object> output,
        Usage usage,
        Outcome outcome,
        Map<String, Object> approvalRequest
) {

    public enum Outcome {
        /** The node produced its final output. */
        COMPLETED,
        /** The node needs a human decision before it can produce output. */
        AWAIT_APPROVAL
    }

    public NodeResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        usage = usage == null ? Usage.ZERO : usage;
        outcome = outcome == null ? Outcome.COMPLETED : outcome;
        approvalRequest = approvalRequest == null ? Map.of() : Map.copyOf(approvalRequest);
    }

    public static NodeResult of(Map<String, Object> output) {
        return new NodeResult(output, Usage.ZERO, Outcome.COMPLETED, Map.of());
    }

    public static NodeResult of(Map<String, Object> output, Usage usage) {
        return new NodeResult(output, usage, Outcome.COMPLETED, Map.of());
    }

    public static NodeResult empty() {
        return new NodeResult(Map.of(), Usage.ZERO, Outcome.COMPLETED, Map.of());
    }

    public static NodeResult awaitingApproval(Map<String, Object> request) {
        return new NodeResult(Map.of(), Usage.ZERO, Outcome.AWAIT_APPROVAL, request);
    }

    public boolean awaitingApproval() {
        return outcome == Outcome.AWAIT_APPROVAL;
    }

    /** Convenience for handlers that want to attach one key without building a map by hand. */
    public static NodeResult single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return of(map);
    }
}
