package io.loomvault.llm.openai;

import java.time.Duration;

/**
 * Connection settings for an OpenAI-compatible endpoint.
 *
 * <p>One client covers a surprising amount of ground: OpenAI, DeepSeek, Qwen/DashScope,
 * Moonshot, Together, Groq, and self-hosted Ollama or vLLM all speak the same
 * {@code /v1/chat/completions} dialect. Only the base URL, key and model id change.</p>
 *
 * @param baseUrl      root of the API, e.g. {@code https://api.openai.com/v1} — trailing slashes
 *                     are tolerated
 * @param apiKey       bearer token; local servers usually ignore it, so an arbitrary non-blank
 *                     value such as {@code "ollama"} is fine
 * @param timeout      per-request timeout; this is the value that keeps a hung provider from
 *                     pinning a node slot forever
 * @param enabled      whether this provider should be wired at all
 */
public record OpenAiConfig(
        String baseUrl,
        String apiKey,
        Duration timeout,
        boolean enabled
) {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    public OpenAiConfig {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        apiKey = apiKey == null ? "" : apiKey.strip();
        timeout = (timeout == null || timeout.isZero() || timeout.isNegative())
                ? Duration.ofSeconds(60)
                : timeout;
    }

    public static OpenAiConfig disabled() {
        return new OpenAiConfig(DEFAULT_BASE_URL, "", Duration.ofSeconds(60), false);
    }

    public static OpenAiConfig of(String baseUrl, String apiKey) {
        return new OpenAiConfig(baseUrl, apiKey, Duration.ofSeconds(60), true);
    }

    public static OpenAiConfig of(String baseUrl, String apiKey, Duration timeout) {
        return new OpenAiConfig(baseUrl, apiKey, timeout, true);
    }

    /** Fully-qualified chat completions endpoint. */
    public String chatCompletionsUrl() {
        return baseUrl + "/chat/completions";
    }

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    /** Hostname, safe to log and show in the UI (never the key). */
    public String host() {
        try {
            return java.net.URI.create(baseUrl).getHost();
        } catch (RuntimeException ex) {
            return baseUrl;
        }
    }
}
