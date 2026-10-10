package io.loomvault.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * All tunables of the server, bound from the {@code loomvault.*} prefix.
 *
 * <p>Kept as a single immutable record so that "what can be configured" is answerable by reading
 * one file. Nested records mirror the YAML structure; the compact constructors carry the defaults
 * so that a partially-filled config file still produces a working application.</p>
 */
@ConfigurationProperties(prefix = "loomvault")
public record LoomVaultProperties(
        Engine engine,
        Store store,
        Cache cache,
        Llm llm,
        Workflows workflows,
        Pricing pricing
) {

    public LoomVaultProperties {
        engine = engine == null ? Engine.defaults() : engine;
        store = store == null ? new Store("jdbc") : store;
        cache = cache == null ? Cache.defaults() : cache;
        llm = llm == null ? Llm.defaults() : llm;
        workflows = workflows == null ? Workflows.defaults() : workflows;
        pricing = pricing == null ? new Pricing(Map.of()) : pricing;
    }

    // -----------------------------------------------------------------------------------------

    /** Scheduler tuning handed straight to {@code EngineOptions}. */
    public record Engine(
            Integer maxParallelNodes,
            Integer defaultMaxAttempts,
            Duration pollInterval,
            Duration hardTimeoutSlack,
            Boolean recoverOnStartup
    ) {

        public Engine {
            maxParallelNodes = maxParallelNodes == null || maxParallelNodes < 1 ? 4 : maxParallelNodes;
            defaultMaxAttempts = defaultMaxAttempts == null || defaultMaxAttempts < 1 ? 2 : defaultMaxAttempts;
            pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
            hardTimeoutSlack = hardTimeoutSlack == null ? Duration.ofSeconds(30) : hardTimeoutSlack;
            recoverOnStartup = recoverOnStartup == null || recoverOnStartup;
        }

        public static Engine defaults() {
            return new Engine(null, null, null, null, null);
        }
    }

    /** {@code jdbc} persists to the configured database; {@code memory} keeps everything local. */
    public record Store(String mode) {

        public Store {
            mode = normalise(mode, "jdbc");
        }

        public boolean isMemory() {
            return "memory".equals(mode);
        }
    }

    /** LLM response cache backend. {@code redis} is required for multi-instance deployments. */
    public record Cache(String mode, Duration ttl, Integer maxEntries, Boolean cacheNonDeterministic) {

        public Cache {
            mode = normalise(mode, "memory");
            ttl = ttl == null ? Duration.ofHours(6) : ttl;
            maxEntries = maxEntries == null || maxEntries < 1 ? 2_000 : maxEntries;
            cacheNonDeterministic = cacheNonDeterministic != null && cacheNonDeterministic;
        }

        public static Cache defaults() {
            return new Cache(null, null, null, null);
        }

        public boolean isRedis() {
            return "redis".equals(mode);
        }
    }

    /** Model gateway selection and per-provider settings. */
    public record Llm(String provider, OpenAi openai, Mock mock, Resilience resilience) {

        public Llm {
            provider = normalise(provider, "mock");
            openai = openai == null ? OpenAi.defaults() : openai;
            mock = mock == null ? Mock.defaults() : mock;
            resilience = resilience == null ? Resilience.defaults() : resilience;
        }

        public static Llm defaults() {
            return new Llm(null, null, null, null);
        }

        public record OpenAi(String baseUrl, String apiKey, Duration timeout) {

            public OpenAi {
                baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com/v1" : baseUrl.strip();
                apiKey = apiKey == null ? "" : apiKey.strip();
                timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
            }

            public static OpenAi defaults() {
                return new OpenAi(null, null, null);
            }
        }

        public record Mock(Duration minLatency, Duration maxLatency, Integer failFirstCalls) {

            public Mock {
                minLatency = minLatency == null ? Duration.ofMillis(120) : minLatency;
                maxLatency = maxLatency == null ? Duration.ofMillis(420) : maxLatency;
                failFirstCalls = failFirstCalls == null || failFirstCalls < 0 ? 0 : failFirstCalls;
            }

            public static Mock defaults() {
                return new Mock(null, null, null);
            }
        }

        public record Resilience(Integer failureThreshold, Duration openDuration, Integer maxAttempts) {

            public Resilience {
                failureThreshold = failureThreshold == null || failureThreshold < 1 ? 5 : failureThreshold;
                openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
                maxAttempts = maxAttempts == null || maxAttempts < 1 ? 2 : maxAttempts;
            }

            public static Resilience defaults() {
                return new Resilience(null, null, null);
            }
        }
    }

    /** Sample workflow loading on startup. */
    public record Workflows(Boolean loadSamples, Boolean runDemoOnStartup, String demoWorkflowId,
                            String demoInputTicket, String location) {

        public Workflows {
            loadSamples = loadSamples == null || loadSamples;
            runDemoOnStartup = runDemoOnStartup == null || runDemoOnStartup;
            demoWorkflowId = (demoWorkflowId == null || demoWorkflowId.isBlank())
                    ? "support-triage" : demoWorkflowId.strip();
            demoInputTicket = (demoInputTicket == null || demoInputTicket.isBlank())
                    ? "TCK-1001" : demoInputTicket.strip();
            location = (location == null || location.isBlank()) ? "classpath:workflows/*.json" : location;
        }

        public static Workflows defaults() {
            return new Workflows(null, null, null, null, null);
        }
    }

    /** Operator-supplied price overrides, keyed by model id. */
    public record Pricing(Map<String, ModelPrice> overrides) {

        public Pricing {
            overrides = overrides == null ? Map.of() : new LinkedHashMap<>(overrides);
        }

        public record ModelPrice(String inputPerMillion, String outputPerMillion) {
        }
    }

    private static String normalise(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip().toLowerCase(Locale.ROOT);
    }
}
