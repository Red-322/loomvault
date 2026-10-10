package io.loomvault.core.llm;

import java.util.Optional;

/**
 * Content-addressed response cache.
 *
 * <p>Keys are {@link ChatRequest#cacheKey()} values. A cache hit costs zero tokens and zero
 * dollars, which is exactly why the engine reports it as a distinct counter instead of quietly
 * folding it into "llmCalls".</p>
 *
 * <p>Semantics of caching non-deterministic calls ({@code temperature > 0}): implementations
 * should store them, but callers must not assume reproducibility. The engine only <em>replays</em>
 * responses it recorded in the journal; the cache merely avoids paying twice for the same prompt
 * inside a rolling window.</p>
 */
public interface ResponseCache {

    Optional<ChatResponse> get(String key);

    void put(String key, ChatResponse response, java.time.Duration ttl);

    long hitCount();

    long missCount();

    /** Removes everything. Exposed for tests and the admin endpoint. */
    void clear();

    /** Cache that stores nothing — the correct default when no cache backend is configured. */
    ResponseCache NOOP = new ResponseCache() {
        @Override
        public Optional<ChatResponse> get(String key) {
            return Optional.empty();
        }

        @Override
        public void put(String key, ChatResponse response, java.time.Duration ttl) {
            // intentionally empty
        }

        @Override
        public long hitCount() {
            return 0L;
        }

        @Override
        public long missCount() {
            return 0L;
        }

        @Override
        public void clear() {
            // intentionally empty
        }
    };
}
