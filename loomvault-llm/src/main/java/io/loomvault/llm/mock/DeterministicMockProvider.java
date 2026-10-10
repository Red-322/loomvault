package io.loomvault.llm.mock;

import io.loomvault.core.error.LlmException;
import io.loomvault.core.llm.ChatRequest;
import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.llm.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline, deterministic model provider.
 *
 * <p>Serves every LLM node without a network connection, which is what allows
 * {@code docker compose up} to run a complete workflow end to end, and CI to assert on exact
 * token counts. Responses are a pure function of the request, so two runs of the same execution
 * produce identical journals.</p>
 *
 * <p>Latency is simulated (and therefore <em>not</em> deterministic) to keep parallel branches
 * honest; token counts are estimated from the prompt and completion length using
 * {@link MockOptions#tokensPerChar()}. Real providers report exact usage — the mock's numbers are
 * labelled as estimates in the response metadata, and cost figures derived from them are
 * indicative only.</p>
 */
public final class DeterministicMockProvider implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(DeterministicMockProvider.class);

    private final MockOptions options;
    private final AtomicInteger callCounter = new AtomicInteger();

    public DeterministicMockProvider() {
        this(MockOptions.defaults());
    }

    public DeterministicMockProvider(MockOptions options) {
        this.options = options == null ? MockOptions.defaults() : options;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        int call = callCounter.incrementAndGet();

        if (call <= options.failFirstCalls()) {
            // A retryable failure: this is what exercises backoff and the journal's NODE_FAILED
            // entries in the self-test workflow.
            log.debug("mock.injected_failure call={} of={}", call, options.failFirstCalls());
            throw new LlmException(
                    "mock provider: injected transient failure on call " + call
                            + " (failFirstCalls=" + options.failFirstCalls() + ")",
                    true, 503, null);
        }

        simulateLatency();

        String content = MockScripts.respond(request);
        TokenUsage usage = estimateUsage(request, content);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "mock");
        metadata.put("deterministic", true);
        metadata.put("estimatedUsage", true);
        metadata.put("callNumber", call);

        log.debug("mock.complete model={} promptChars={} completionChars={} tokens={}",
                request.model(), request.promptCharacters(), content.length(), usage.total());

        return new ChatResponse(content, request.model(), usage, false, "stop", metadata);
    }

    /** Number of calls served so far; exposed for tests and the demo self-check endpoint. */
    public int callCount() {
        return callCounter.get();
    }

    private void simulateLatency() {
        long min = options.minLatencyMs();
        long max = options.maxLatencyMs();
        if (max <= 0L) {
            return;
        }
        long delay = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmException("mock latency interrupted", false, -1, ex);
        }
    }

    private TokenUsage estimateUsage(ChatRequest request, String completion) {
        double ratio = options.tokensPerChar();
        long input = Math.max(1L, Math.round(request.promptCharacters() * ratio));
        long output = Math.max(1L, Math.round(completion.length() * ratio));
        return new TokenUsage(input, output);
    }
}
