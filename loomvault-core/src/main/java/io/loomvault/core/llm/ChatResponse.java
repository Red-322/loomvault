package io.loomvault.core.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Provider-agnostic completion result.
 *
 * @param content      the generated text
 * @param model        the model that actually served the call (may differ from the requested one
 *                     after a budget-driven DEGRADE)
 * @param usage        token accounting as reported by the provider
 * @param fromCache    true when the response was served from the content-addressed cache
 * @param finishReason provider stop reason ({@code stop}, {@code length}, ...), may be null
 * @param metadata     provider-specific extras kept for debugging
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
        String content,
        String model,
        TokenUsage usage,
        boolean fromCache,
        String finishReason,
        Map<String, Object> metadata
) {

    public ChatResponse {
        content = content == null ? "" : content;
        usage = usage == null ? TokenUsage.EMPTY : usage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ChatResponse of(String content, String model, TokenUsage usage) {
        return new ChatResponse(content, model, usage, false, "stop", Map.of());
    }

    /** Marks this response as served from cache; cached responses bill zero tokens. */
    public ChatResponse asCached() {
        return new ChatResponse(content, model, TokenUsage.EMPTY, true, finishReason, metadata);
    }

    public boolean truncated() {
        return "length".equalsIgnoreCase(finishReason);
    }
}
