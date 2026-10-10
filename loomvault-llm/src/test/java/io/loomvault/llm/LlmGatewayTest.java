package io.loomvault.llm;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LlmException;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.llm.ChatMessage;
import io.loomvault.core.llm.ChatRequest;
import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.llm.TokenUsage;
import io.loomvault.core.model.RetryPolicy;
import io.loomvault.llm.cache.InMemoryResponseCache;
import io.loomvault.llm.decorator.CachingLlmGateway;
import io.loomvault.llm.decorator.CircuitBreaker;
import io.loomvault.llm.decorator.ResilientLlmGateway;
import io.loomvault.llm.mock.DeterministicMockProvider;
import io.loomvault.llm.mock.MockOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour of the model gateway layer: determinism, cache keying, the LRU/TTL cache, the caching
 * decorator, the circuit breaker and the resilience decorator.
 *
 * <p>No sleeps are used. Time-dependent behaviour is driven by a {@link MutableClock} so TTL and
 * breaker cooldowns are tested deterministically instead of by wall clock.</p>
 */
class LlmGatewayTest {

    private static final ChatRequest REQUEST =
            ChatRequest.of("mock-fast", 0d, ChatMessage.user("classify this support ticket"));

    // =========================================================================================
    // Determinism
    // =========================================================================================

    @Test
    @DisplayName("the mock provider returns byte-identical content for the same request")
    void shouldBeDeterministicForIdenticalRequests() {
        DeterministicMockProvider provider = new DeterministicMockProvider(MockOptions.instant());

        ChatResponse first = provider.complete(REQUEST);
        ChatResponse second = provider.complete(REQUEST);

        assertThat(provider.provider()).isEqualTo("mock");
        assertThat(first.content()).isEqualTo(second.content()).isNotBlank();
        assertThat(first.usage()).isEqualTo(second.usage());
        assertThat(provider.callCount()).isEqualTo(2);
    }

    // =========================================================================================
    // Cache key
    // =========================================================================================

    @Nested
    @DisplayName("ChatRequest.cacheKey()")
    class CacheKey {

        @Test
        @DisplayName("is stable for equal content")
        void shouldBeStableForEqualContent() {
            ChatRequest other = ChatRequest.of("mock-fast", 0d, ChatMessage.user("classify this support ticket"));
            assertThat(other.cacheKey()).isEqualTo(REQUEST.cacheKey());
        }

        @Test
        @DisplayName("changes when the temperature changes")
        void shouldChangeWithTemperature() {
            ChatRequest warmer = ChatRequest.of("mock-fast", 0.7d, ChatMessage.user("classify this support ticket"));
            assertThat(warmer.cacheKey()).isNotEqualTo(REQUEST.cacheKey());
        }

        @Test
        @DisplayName("changes when the model changes")
        void shouldChangeWithModel() {
            ChatRequest otherModel = ChatRequest.of("mock-smart", 0d, ChatMessage.user("classify this support ticket"));
            assertThat(otherModel.cacheKey()).isNotEqualTo(REQUEST.cacheKey());
        }

        @Test
        @DisplayName("changes when the messages change")
        void shouldChangeWithMessages() {
            ChatRequest otherMessage = ChatRequest.of("mock-fast", 0d, ChatMessage.user("a different prompt"));
            assertThat(otherMessage.cacheKey()).isNotEqualTo(REQUEST.cacheKey());
        }

        @Test
        @DisplayName("changes when the response format changes")
        void shouldChangeWithResponseFormat() {
            ChatRequest json = new ChatRequest("mock-fast",
                    List.of(ChatMessage.user("classify this support ticket")), 0d, 0, "json");
            assertThat(json.cacheKey()).isNotEqualTo(REQUEST.cacheKey());
        }
    }

    // =========================================================================================
    // InMemoryResponseCache
    // =========================================================================================

    @Nested
    @DisplayName("InMemoryResponseCache")
    class Cache {

        private final ChatResponse response = ChatResponse.of("cached", "mock-fast", TokenUsage.of(3, 4));

        @Test
        @DisplayName("counts hits and misses")
        void shouldCountHitsAndMisses() {
            InMemoryResponseCache cache = new InMemoryResponseCache(10, Clock.systemUTC());

            assertThat(cache.get("missing")).isEmpty();
            assertThat(cache.missCount()).isEqualTo(1);
            assertThat(cache.hitCount()).isZero();

            cache.put("k", response, Duration.ofMinutes(5));
            assertThat(cache.get("k")).contains(response);
            assertThat(cache.hitCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("expires entries past their TTL")
        void shouldExpireEntriesPastTtl() {
            MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
            InMemoryResponseCache cache = new InMemoryResponseCache(10, clock);

            cache.put("k", response, Duration.ofSeconds(1));
            assertThat(cache.get("k")).isPresent();

            clock.advance(Duration.ofSeconds(2));

            assertThat(cache.get("k")).isEmpty();
            assertThat(cache.size()).isZero();
        }

        @Test
        @DisplayName("evicts the least recently used entry when capacity is reached")
        void shouldEvictLeastRecentlyUsedEntry() {
            InMemoryResponseCache cache = new InMemoryResponseCache(2, Clock.systemUTC());

            cache.put("k1", response, Duration.ofMinutes(5));
            cache.put("k2", response, Duration.ofMinutes(5));
            cache.put("k3", response, Duration.ofMinutes(5)); // evicts k1 (the eldest)

            assertThat(cache.size()).isEqualTo(2);
            assertThat(cache.evictionCount()).isEqualTo(1);
            assertThat(cache.get("k1")).isEmpty();
            assertThat(cache.get("k2")).isPresent();
            assertThat(cache.get("k3")).isPresent();
        }
    }

    // =========================================================================================
    // CachingLlmGateway
    // =========================================================================================

    @Nested
    @DisplayName("CachingLlmGateway")
    class Caching {

        private final ChatResponse response = ChatResponse.of("hello", "mock-fast", TokenUsage.of(10, 5));

        @Test
        @DisplayName("serves a deterministic request from cache and zeroes the usage")
        void shouldCacheDeterministicRequests() {
            FixedGateway delegate = new FixedGateway(response);
            CachingLlmGateway gateway = new CachingLlmGateway(delegate,
                    new InMemoryResponseCache(10, Clock.systemUTC()));

            ChatResponse first = gateway.complete(REQUEST);
            ChatResponse second = gateway.complete(REQUEST);

            assertThat(first.fromCache()).isFalse();
            assertThat(second.fromCache()).isTrue();
            assertThat(second.content()).isEqualTo("hello");
            assertThat(second.usage()).isEqualTo(TokenUsage.EMPTY);
            assertThat(delegate.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("does not cache non-deterministic requests by default")
        void shouldNotCacheNonDeterministicRequestsByDefault() {
            FixedGateway delegate = new FixedGateway(response);
            CachingLlmGateway gateway = new CachingLlmGateway(delegate,
                    new InMemoryResponseCache(10, Clock.systemUTC()));
            ChatRequest sampled = ChatRequest.of("mock-fast", 0.9d, ChatMessage.user("write a poem"));

            ChatResponse first = gateway.complete(sampled);
            ChatResponse second = gateway.complete(sampled);

            assertThat(first.fromCache()).isFalse();
            assertThat(second.fromCache()).isFalse();
            assertThat(delegate.callCount()).isEqualTo(2);
        }
    }

    // =========================================================================================
    // CircuitBreaker
    // =========================================================================================

    @Nested
    @DisplayName("CircuitBreaker")
    class Breaker {

        @Test
        @DisplayName("opens after the failure threshold is reached")
        void shouldOpenAfterConsecutiveFailures() {
            CircuitBreaker breaker = new CircuitBreaker("test", 3, Duration.ofSeconds(10), Clock.systemUTC());

            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordFailure();
            breaker.recordFailure();
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordFailure();

            assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(breaker.tripCount()).isEqualTo(1);
            assertThat(breaker.tryAcquire()).isFalse();
            assertThat(breaker.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("half-opens after the cooldown and admits exactly one probe")
        void shouldAdmitExactlyOneProbeAfterCooldown() {
            MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
            CircuitBreaker breaker = new CircuitBreaker("test", 2, Duration.ofSeconds(10), clock);
            breaker.recordFailure();
            breaker.recordFailure();
            assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

            clock.advance(Duration.ofSeconds(11));

            assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            assertThat(breaker.tryAcquire()).isTrue();  // the single probe
            assertThat(breaker.tryAcquire()).isFalse(); // a second caller is rejected
        }

        @Test
        @DisplayName("closes again when the probe succeeds")
        void shouldCloseOnProbeSuccess() {
            MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
            CircuitBreaker breaker = new CircuitBreaker("test", 2, Duration.ofSeconds(10), clock);
            breaker.recordFailure();
            breaker.recordFailure();
            clock.advance(Duration.ofSeconds(11));
            assertThat(breaker.tryAcquire()).isTrue();

            breaker.recordSuccess();

            assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(breaker.tryAcquire()).isTrue();
            assertThat(breaker.consecutiveFailures()).isZero();
        }
    }

    // =========================================================================================
    // ResilientLlmGateway
    // =========================================================================================

    @Nested
    @DisplayName("ResilientLlmGateway")
    class Resilience {

        private final CircuitBreaker breaker =
                new CircuitBreaker("test", 10, Duration.ofSeconds(30), Clock.systemUTC());
        private final RetryPolicy retry = RetryPolicy.fixed(3, Duration.ZERO);

        @Test
        @DisplayName("retries a retryable failure up to the configured attempts")
        void shouldRetryRetryableFailures() {
            ScriptedGateway delegate = new ScriptedGateway(
                    new LlmException("transient 1", true, 503, null),
                    new LlmException("transient 2", true, 503, null),
                    ChatResponse.of("recovered", "mock-fast", TokenUsage.of(1, 1)));
            ResilientLlmGateway gateway = new ResilientLlmGateway(delegate, breaker, retry);

            ChatResponse response = gateway.complete(REQUEST);

            assertThat(response.content()).isEqualTo("recovered");
            assertThat(delegate.callCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("propagates a non-retryable failure immediately without retrying")
        void shouldNotRetryNonRetryableFailures() {
            ScriptedGateway delegate = new ScriptedGateway(
                    new LlmException("bad request", false, 400, null));
            ResilientLlmGateway gateway = new ResilientLlmGateway(delegate, breaker, retry);

            assertThatThrownBy(() -> gateway.complete(REQUEST))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("bad request");
            assertThat(delegate.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("fails fast when the breaker is open, without touching the provider")
        void shouldFailFastWhenBreakerIsOpen() {
            ScriptedGateway delegate = new ScriptedGateway(new LlmException("down", true, 503, null));
            CircuitBreaker openBreaker = new CircuitBreaker("open", 1, Duration.ofMinutes(5), Clock.systemUTC());
            openBreaker.recordFailure(); // trips after a single failure
            ResilientLlmGateway gateway = new ResilientLlmGateway(delegate, openBreaker, retry);

            assertThatThrownBy(() -> gateway.complete(REQUEST))
                    .isInstanceOfSatisfying(LoomVaultException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.LLM_CIRCUIT_OPEN));
            assertThat(delegate.callCount()).isZero();
        }
    }

    // =========================================================================================
    // Test doubles
    // =========================================================================================

    /** A clock whose instant can be advanced by tests. */
    static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Gateway that always returns the same response and counts its calls. */
    static final class FixedGateway implements LlmGateway {

        private final ChatResponse response;
        private final AtomicInteger calls = new AtomicInteger();

        FixedGateway(ChatResponse response) {
            this.response = response;
        }

        @Override
        public String provider() {
            return "fixed";
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            calls.incrementAndGet();
            return response;
        }

        int callCount() {
            return calls.get();
        }
    }

    /** Gateway that replays a script of responses and exceptions, counting its calls. */
    static final class ScriptedGateway implements LlmGateway {

        private final Deque<Object> script = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedGateway(Object... steps) {
            script.addAll(Arrays.asList(steps));
        }

        @Override
        public String provider() {
            return "scripted";
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            calls.incrementAndGet();
            Object next = script.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            if (next instanceof ChatResponse response) {
                return response;
            }
            throw new IllegalStateException("scripted gateway exhausted after " + calls.get() + " call(s)");
        }

        int callCount() {
            return calls.get();
        }
    }
}
