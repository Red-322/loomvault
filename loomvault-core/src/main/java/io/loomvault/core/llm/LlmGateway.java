package io.loomvault.core.llm;

/**
 * The single seam between the engine and any model provider.
 *
 * <p>LoomVault only ever talks to this interface. The bundled implementations are the offline
 * {@code DeterministicMockProvider} (used by the one-command demo, the tests and CI) and the
 * {@code OpenAiCompatibleProvider} (works with OpenAI, DeepSeek, Qwen, Ollama, vLLM — anything
 * that speaks the {@code /v1/chat/completions} dialect). Cross-cutting concerns — caching,
 * circuit breaking, retries — are added by decorators, so a new provider never re-implements
 * them.</p>
 */
public interface LlmGateway {

    /**
     * Executes a completion call.
     *
     * @throws io.loomvault.core.error.LlmException on provider errors; the exception carries a
     *         {@code retryable} flag so the engine can skip pointless retries on 4xx responses
     */
    ChatResponse complete(ChatRequest request);

    /** Human-readable provider name, surfaced in metrics and API responses. */
    String provider();
}
