package io.loomvault.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.loomvault.core.error.LlmException;
import io.loomvault.core.json.LoomJson;
import io.loomvault.core.llm.ChatMessage;
import io.loomvault.core.llm.ChatRequest;
import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.llm.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model gateway for any provider that implements the OpenAI chat-completions dialect.
 *
 * <p>Uses the JDK's own {@link HttpClient} rather than a vendor SDK. For a single POST endpoint
 * that decision buys: no transitive dependency tree, no version pinning against a moving SDK, and
 * a request body the reader can inspect in twenty lines. The cost is that streaming and
 * provider-specific extensions have to be added by hand if they are ever needed.</p>
 *
 * <h3>Error classification</h3>
 * <p>The {@code retryable} flag on {@link LlmException} is set from the HTTP status: 408, 409,
 * 425, 429 and all 5xx are retryable; everything else is a bug in the request and is failed
 * immediately. Getting this wrong in either direction is expensive — either you pay for the same
 * 400 three times, or you give up on a provider that was merely rate-limiting you.</p>
 */
public final class OpenAiCompatibleProvider implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final int MAX_ERROR_BODY_CHARS = 600;

    private final OpenAiConfig config;
    private final HttpClient http;

    public OpenAiCompatibleProvider(OpenAiConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String provider() {
        return "openai-compatible:" + config.host();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(config.chatCompletionsUrl()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(LoomJson.write(buildBody(request)), StandardCharsets.UTF_8))
                .build();

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            // Connection refused, DNS failure, read timeout: all worth another try.
            throw new LlmException("provider unreachable: " + ex.getMessage(), true, -1, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmException("provider call interrupted", false, -1, ex);
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        if (response.statusCode() >= 400) {
            throw new LlmException(
                    "provider returned HTTP " + response.statusCode() + ": " + truncate(response.body()),
                    isRetryableStatus(response.statusCode()),
                    response.statusCode(),
                    null);
        }

        ChatResponse parsed = parse(response.body(), request, elapsedMs);
        log.debug("llm.openai.complete model={} status={} elapsedMs={} tokens={}",
                parsed.model(), response.statusCode(), elapsedMs, parsed.usage().total());
        return parsed;
    }

    private Map<String, Object> buildBody(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());

        List<Map<String, String>> messages = new ArrayList<>(request.messages().size());
        for (ChatMessage message : request.messages()) {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("role", message.role());
            payload.put("content", message.content());
            messages.add(payload);
        }
        body.put("messages", messages);

        if (request.temperature() > 0d) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.expectsJson()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    private ChatResponse parse(String rawBody, ChatRequest request, long elapsedMs) {
        JsonNode root;
        try {
            root = LoomJson.readTree(rawBody);
        } catch (RuntimeException ex) {
            throw new LlmException("provider returned unparsable JSON: " + truncate(rawBody), true, 200, ex);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("provider response contains no choices: " + truncate(rawBody), true, 200, null);
        }
        JsonNode first = choices.get(0);
        String content = first.path("message").path("content").asText("");
        String finishReason = first.path("finish_reason").asText("stop");
        String model = root.path("model").asText(request.model());

        JsonNode usageNode = root.path("usage");
        long inputTokens = usageNode.path("prompt_tokens").asLong(0L);
        long outputTokens = usageNode.path("completion_tokens").asLong(0L);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider());
        metadata.put("elapsedMs", elapsedMs);
        metadata.put("requestedModel", request.model());

        return new ChatResponse(content, model, new TokenUsage(inputTokens, outputTokens),
                false, finishReason, metadata);
    }

    /** 408 timeout, 409 conflict, 425 too early, 429 rate limit and every 5xx are retryable. */
    static boolean isRetryableStatus(int status) {
        return status >= 500 || status == 408 || status == 409 || status == 425 || status == 429;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").strip();
        return compact.length() <= MAX_ERROR_BODY_CHARS
                ? compact
                : compact.substring(0, MAX_ERROR_BODY_CHARS) + "…";
    }

    public OpenAiConfig config() {
        return config;
    }

    /** Exposed so callers can build a matching probe request for health checks. */
    public Duration timeout() {
        return config.timeout();
    }
}
