package io.loomvault.llm.decorator;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LlmException;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.llm.ChatRequest;
import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.model.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Decorator adding call-level retries and circuit breaking in front of a gateway.
 *
 * <h3>Two layers of retry, on purpose</h3>
 * <p>The engine has a node-level retry policy; this decorator adds a much shorter, cheaper
 * call-level retry. They solve different problems:</p>
 * <ul>
 *   <li><b>Call-level</b> (here, 1–3 fast attempts) absorbs transient blips without the engine
 *       ever noticing — no journal churn, no node re-scheduling.</li>
 *   <li><b>Node-level</b> (in the workflow definition, seconds of backoff) handles failures the
 *       gateway cannot fix, and leaves an audit trail in the journal.</li>
 * </ul>
 * <p>Keep the product of the two small. {@code 3 × 4 = 12} provider calls for one step is how a
 * "small retry" turns into a four-figure bill.</p>
 *
 * <p>Non-retryable failures ({@code LlmException.retryable() == false}, e.g. HTTP 400/401) are
 * propagated immediately — retrying a malformed request only produces the same malformed request
 * more slowly.</p>
 */
public final class ResilientLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmGateway.class);

    private final LlmGateway delegate;
    private final CircuitBreaker breaker;
    private final RetryPolicy retry;

    public ResilientLlmGateway(LlmGateway delegate, CircuitBreaker breaker, RetryPolicy retry) {
        this.delegate = delegate;
        this.breaker = breaker == null ? CircuitBreaker.defaults("llm") : breaker;
        this.retry = retry == null ? RetryPolicy.exponential(2, Duration.ofMillis(150), 2.0d) : retry;
    }

    /** Sensible default: two attempts (one retry) with jittered backoff, five failures to trip. */
    public static ResilientLlmGateway around(LlmGateway delegate, String name) {
        return new ResilientLlmGateway(delegate, CircuitBreaker.defaults(name),
                RetryPolicy.exponential(2, Duration.ofMillis(150), 2.0d));
    }

    @Override
    public String provider() {
        return delegate.provider() + "+resilient";
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        if (!breaker.tryAcquire()) {
            throw new LoomVaultException(ErrorCode.LLM_CIRCUIT_OPEN,
                    "circuit breaker '" + breaker.name() + "' is open; refusing to call model '"
                            + request.model() + "' (rejected so far: " + breaker.rejectedCount() + ")",
                    null, Map.of("provider", delegate.provider(), "state", breaker.state().name()));
        }

        LlmException lastFailure = null;
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            try {
                ChatResponse response = delegate.complete(request);
                breaker.recordSuccess();
                return response;
            } catch (LlmException ex) {
                lastFailure = ex;
                breaker.recordFailure();
                if (!ex.retryable() || attempt >= retry.maxAttempts()) {
                    throw ex;
                }
                Duration backoff = retry.backoffFor(attempt);
                log.debug("llm.retry attempt={}/{} backoffMs={} reason={}",
                        attempt, retry.maxAttempts(), backoff.toMillis(), ex.getMessage());
                if (!sleep(backoff)) {
                    throw new LlmException("retry interrupted", false, -1, ex);
                }
            } catch (RuntimeException ex) {
                // Unexpected (non-LLM) failure: count it and let the engine see the original.
                breaker.recordFailure();
                throw ex;
            }
        }
        throw lastFailure == null
                ? new LlmException("llm call failed with no recorded cause", false, -1, null)
                : lastFailure;
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    private static boolean sleep(Duration duration) {
        if (duration == null || duration.isZero()) {
            return true;
        }
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
