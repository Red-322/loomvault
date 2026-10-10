package io.loomvault.core.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Provider-agnostic completion request.
 *
 * <p>{@link #cacheKey()} is the content-addressed identity of the call: two requests with the
 * same messages, model and sampling parameters produce the same key, which is what makes the
 * response cache and deterministic replay possible. The key deliberately includes
 * {@code temperature}: a call at {@code temperature > 0} is not reproducible, and the cache
 * layers treat such responses as advisory only.</p>
 *
 * @param model          provider model id, e.g. {@code gpt-4o-mini}, {@code mock-fast}
 * @param messages       conversation so far; at least one message is required
 * @param temperature    sampling temperature; {@code 0} means deterministic
 * @param maxTokens      upper bound on completion length; {@code 0} = provider default
 * @param responseFormat {@code text}, {@code json} or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String model,
        java.util.List<ChatMessage> messages,
        double temperature,
        int maxTokens,
        String responseFormat
) {

    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        messages = messages == null ? java.util.List.of() : java.util.List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a chat request needs at least one message");
        }
    }

    public static ChatRequest of(String model, double temperature, ChatMessage... messages) {
        return new ChatRequest(model, java.util.List.of(messages), temperature, 0, null);
    }

    /** True when the same input is expected to yield the same output (temperature 0). */
    public boolean deterministic() {
        return temperature <= 0d;
    }

    public boolean expectsJson() {
        return responseFormat != null && responseFormat.toLowerCase().contains("json");
    }

    /** Stable SHA-256 fingerprint over everything that affects the completion. */
    public String cacheKey() {
        StringBuilder canonical = new StringBuilder(256);
        canonical.append("v1|").append(model)
                .append('|').append(temperature)
                .append('|').append(maxTokens)
                .append('|').append(responseFormat == null ? "" : responseFormat)
                .append('|');
        for (ChatMessage message : messages) {
            canonical.append(message.role()).append(':').append(message.content()).append('\u241E');
        }
        return io.loomvault.core.util.Hashing.sha256Hex(canonical.toString());
    }

    /** Rough prompt size in characters — used by the mock provider and by cost previews. */
    public int promptCharacters() {
        return messages.stream().mapToInt(m -> m.content().length()).sum();
    }
}
