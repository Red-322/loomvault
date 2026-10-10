package io.loomvault.core.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One message in a chat-style completion request.
 *
 * @param role    {@code system}, {@code user} or {@code assistant}
 * @param content the text payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(String role, String content) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public ChatMessage {
        role = (role == null || role.isBlank()) ? ROLE_USER : role.trim().toLowerCase();
        content = content == null ? "" : content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }
}
