package io.loomvault.llm.cache;

import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.ResponseCache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local LRU response cache with per-entry TTL.
 *
 * <p>Backed by an access-ordered {@link LinkedHashMap} guarded by a single monitor. That sounds
 * unfashionable next to a concurrent map, but the critical section is a handful of pointer
 * operations — contention is irrelevant at the call rates this cache serves, and the LRU ordering
 * that a {@code ConcurrentHashMap} cannot provide is the whole point. A cache that never evicts is
 * a memory leak with extra steps.</p>
 *
 * <p>For multi-instance deployments use the Redis-backed implementation instead; this one is the
 * right default for single-node runs and tests.</p>
 */
public final class InMemoryResponseCache implements ResponseCache {

    private static final int DEFAULT_MAX_ENTRIES = 2_000;

    private final int maxEntries;
    private final Map<String, Entry> store;
    private final Clock clock;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    private record Entry(ChatResponse response, Instant expiresAt) {

        boolean expiredAt(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }

    public InMemoryResponseCache() {
        this(DEFAULT_MAX_ENTRIES, Clock.systemUTC());
    }

    public InMemoryResponseCache(int maxEntries, Clock clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.store = new LinkedHashMap<>(64, 0.75f, true);
    }

    @Override
    public synchronized Optional<ChatResponse> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (entry.expiredAt(clock.instant())) {
            store.remove(key);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(entry.response());
    }

    @Override
    public synchronized void put(String key, ChatResponse response, Duration ttl) {
        if (key == null || response == null) {
            return;
        }
        Instant expiresAt = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? null
                : clock.instant().plus(ttl);
        store.put(key, new Entry(response, expiresAt));
        while (store.size() > maxEntries) {
            Iterator<String> eldest = store.keySet().iterator();
            if (!eldest.hasNext()) {
                break;
            }
            eldest.next();
            eldest.remove();
            evictions.incrementAndGet();
        }
    }

    @Override
    public long hitCount() {
        return hits.get();
    }

    @Override
    public long missCount() {
        return misses.get();
    }

    /** Number of entries dropped because the capacity was reached. */
    public long evictionCount() {
        return evictions.get();
    }

    public synchronized int size() {
        return store.size();
    }

    @Override
    public synchronized void clear() {
        store.clear();
    }
}
