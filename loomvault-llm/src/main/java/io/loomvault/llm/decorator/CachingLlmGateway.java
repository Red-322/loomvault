package io.loomvault.llm.decorator;

import io.loomvault.core.llm.ChatRequest;
import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.llm.ResponseCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Decorator that serves identical requests from the content-addressed cache.
 *
 * <p>Only deterministic requests ({@code temperature = 0}) are cached by default. This is a
 * correctness decision, not a cost one: caching a sampled response and serving it later silently
 * changes the distribution the caller asked for. {@code cacheNonDeterministic} exists for
 * cost-sensitive use cases where that trade is acceptable, and defaults to off.</p>
 *
 * <p>A hit is returned with {@link ChatResponse#asCached()}, which zeroes the usage counters.
 * The engine reports cache hits as a separate metric precisely so that "cheap" stays visible
 * instead of being averaged away.</p>
 */
public final class CachingLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(CachingLlmGateway.class);

    private final LlmGateway delegate;
    private final ResponseCache cache;
    private final Duration ttl;
    private final boolean cacheNonDeterministic;

    public CachingLlmGateway(LlmGateway delegate, ResponseCache cache) {
        this(delegate, cache, Duration.ofHours(6), false);
    }

    public CachingLlmGateway(LlmGateway delegate, ResponseCache cache, Duration ttl,
                             boolean cacheNonDeterministic) {
        this.delegate = delegate;
        this.cache = cache == null ? ResponseCache.NOOP : cache;
        this.ttl = ttl == null ? Duration.ofHours(6) : ttl;
        this.cacheNonDeterministic = cacheNonDeterministic;
    }

    @Override
    public String provider() {
        return delegate.provider() + "+cache";
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        if (!request.deterministic() && !cacheNonDeterministic) {
            return delegate.complete(request);
        }
        String key = request.cacheKey();
        Optional<ChatResponse> hit = cache.get(key);
        if (hit.isPresent()) {
            log.debug("llm.cache.hit model={} key={}", request.model(), key.substring(0, 12));
            return hit.get().asCached();
        }
        ChatResponse response = delegate.complete(request);
        cache.put(key, response, ttl);
        return response;
    }

    /** The wrapped gateway, for diagnostics endpoints that want to report the real provider. */
    public LlmGateway delegate() {
        return delegate;
    }

    public ResponseCache cache() {
        return cache;
    }
}
