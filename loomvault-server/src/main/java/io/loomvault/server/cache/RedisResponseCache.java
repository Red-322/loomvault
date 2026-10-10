package io.loomvault.server.cache;

import io.loomvault.core.json.LoomJson;
import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.ResponseCache;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed response cache, shared across application instances.
 *
 * <p>TTL is enforced by Redis itself ({@code SET key value PX ttl}) rather than by the client, so
 * an instance that crashes mid-flight cannot leave immortal entries behind. HIT/MISS counters are
 * per-instance and aggregated by Micrometer — good enough for dashboards, and deliberately not a
 * correctness concern.</p>
 */
public final class RedisResponseCache implements ResponseCache {

    private static final String KEY_PREFIX = "loomvault:llm:cache:";

    private final StringRedisTemplate redis;
    private final Duration defaultTtl;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public RedisResponseCache(StringRedisTemplate redis, Duration defaultTtl) {
        this.redis = redis;
        this.defaultTtl = defaultTtl == null ? Duration.ofHours(6) : defaultTtl;
    }

    @Override
    public Optional<ChatResponse> get(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = redis.opsForValue().get(KEY_PREFIX + key);
        } catch (RuntimeException ex) {
            // A cache outage must degrade to "miss", never to a failed execution.
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (raw == null || raw.isBlank()) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        try {
            hits.incrementAndGet();
            return Optional.of(LoomJson.read(raw, ChatResponse.class));
        } catch (RuntimeException ex) {
            misses.incrementAndGet();
            redis.delete(KEY_PREFIX + key);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, ChatResponse response, Duration ttl) {
        if (key == null || key.isBlank() || response == null) {
            return;
        }
        Duration effective = (ttl == null || ttl.isZero() || ttl.isNegative()) ? defaultTtl : ttl;
        try {
            redis.opsForValue().set(KEY_PREFIX + key, LoomJson.write(response), effective);
        } catch (RuntimeException ex) {
            // Same rule as above: a broken cache is a missed optimisation, not an outage.
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

    @Override
    public void clear() {
        try {
            var keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException ex) {
            // Ignore: clearing a cache that is already unreachable is a no-op.
        }
    }
}
