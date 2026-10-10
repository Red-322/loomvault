package io.loomvault.server.config;

import io.loomvault.core.budget.CostModel;
import io.loomvault.core.budget.ModelPricing;
import io.loomvault.core.engine.EngineOptions;
import io.loomvault.core.engine.ExecutionEngine;
import io.loomvault.core.engine.ToolRegistry;
import io.loomvault.core.engine.WorkflowRegistry;
import io.loomvault.core.journal.JournalStore;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.llm.ResponseCache;
import io.loomvault.core.model.RetryPolicy;
import io.loomvault.core.port.ExecutionRepository;
import io.loomvault.llm.cache.InMemoryResponseCache;
import io.loomvault.llm.decorator.CachingLlmGateway;
import io.loomvault.llm.decorator.CircuitBreaker;
import io.loomvault.llm.decorator.ResilientLlmGateway;
import io.loomvault.llm.mock.DeterministicMockProvider;
import io.loomvault.llm.mock.MockOptions;
import io.loomvault.llm.openai.OpenAiCompatibleProvider;
import io.loomvault.llm.openai.OpenAiConfig;
import io.loomvault.server.cache.RedisResponseCache;
import io.loomvault.server.events.ExecutionEventBus;
import io.loomvault.server.tools.BuiltinTools;
import io.loomvault.store.jdbc.JdbcExecutionRepository;
import io.loomvault.store.jdbc.JdbcJournalStore;
import io.loomvault.store.memory.InMemoryExecutionRepository;
import io.loomvault.store.memory.InMemoryJournalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires ports to adapters and assembles the engine.
 *
 * <p>This is the only place in the codebase that knows which concrete implementations exist.
 * {@code loomvault-core} depends on interfaces; swapping PostgreSQL for memory, or the mock
 * provider for a real one, is a configuration change and a bean definition — never a code change
 * in the engine.</p>
 *
 * <h3>Gateway decorator order</h3>
 * <pre>
 *   CachingLlmGateway  →  ResilientLlmGateway  →  (mock | openai)
 * </pre>
 * <p>Cache outside, resilience inside: a cache hit should not consume a circuit-breaker slot, and
 * a retry should not be attempted when the answer is already in the cache.</p>
 */
@Configuration
public class EngineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EngineConfiguration.class);

    // -----------------------------------------------------------------------------------------
    // Durability
    // -----------------------------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "loomvault.store.mode", havingValue = "memory")
    public ExecutionRepository inMemoryExecutionRepository() {
        log.warn("store.mode=memory — executions will not survive a restart");
        return new InMemoryExecutionRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "loomvault.store.mode", havingValue = "jdbc", matchIfMissing = true)
    public ExecutionRepository jdbcExecutionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcExecutionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(name = "loomvault.store.mode", havingValue = "memory")
    public JournalStore inMemoryJournalStore() {
        return new InMemoryJournalStore();
    }

    @Bean
    @ConditionalOnProperty(name = "loomvault.store.mode", havingValue = "jdbc", matchIfMissing = true)
    public JournalStore jdbcJournalStore(JdbcTemplate jdbcTemplate) {
        return new JdbcJournalStore(jdbcTemplate);
    }

    // -----------------------------------------------------------------------------------------
    // Model gateway
    // -----------------------------------------------------------------------------------------

    @Bean
    public CostModel costModel(LoomVaultProperties properties) {
        Map<String, ModelPricing> overrides = new LinkedHashMap<>();
        properties.pricing().overrides().forEach((model, price) ->
                overrides.put(model, new ModelPricing(model,
                        new java.math.BigDecimal(price.inputPerMillion()),
                        new java.math.BigDecimal(price.outputPerMillion()))));
        CostModel model = CostModel.defaults().mergedWith(overrides);
        log.info("cost.model.loaded models={} overrides={}", model.knownModels().size(), overrides.size());
        return model;
    }

    @Bean
    public CircuitBreaker llmCircuitBreaker(LoomVaultProperties properties) {
        LoomVaultProperties.Llm.Resilience resilience = properties.llm().resilience();
        return new CircuitBreaker(properties.llm().provider(),
                resilience.failureThreshold(), resilience.openDuration(), Clock.systemUTC());
    }

    @Bean
    public ResponseCache responseCache(LoomVaultProperties properties,
                                       ObjectProvider<StringRedisTemplate> redisProvider) {
        LoomVaultProperties.Cache cache = properties.cache();
        if (cache.isRedis()) {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null && ping(redis)) {
                log.info("cache.backend=redis ttl={}", cache.ttl());
                return new RedisResponseCache(redis, cache.ttl());
            }
            log.warn("cache.backend=redis requested but Redis is unreachable; "
                    + "falling back to the in-memory cache (hits will not be shared across instances)");
        }
        log.info("cache.backend=memory maxEntries={} ttl={}", cache.maxEntries(), cache.ttl());
        return new InMemoryResponseCache(cache.maxEntries(), Clock.systemUTC());
    }

    @Bean
    public LlmGateway llmGateway(LoomVaultProperties properties,
                                 ResponseCache responseCache,
                                 CircuitBreaker llmCircuitBreaker) {
        LlmGateway base = createBaseGateway(properties);
        RetryPolicy callRetry = RetryPolicy.exponential(
                properties.llm().resilience().maxAttempts(), Duration.ofMillis(150), 2.0d);
        LlmGateway resilient = new ResilientLlmGateway(base, llmCircuitBreaker, callRetry);
        LoomVaultProperties.Cache cache = properties.cache();
        LlmGateway cached = new CachingLlmGateway(resilient, responseCache, cache.ttl(),
                cache.cacheNonDeterministic());
        log.info("llm.gateway provider={} decorators=caching+resilient", base.provider());
        return cached;
    }

    private LlmGateway createBaseGateway(LoomVaultProperties properties) {
        LoomVaultProperties.Llm llm = properties.llm();
        if ("openai".equals(llm.provider())) {
            OpenAiConfig config = new OpenAiConfig(llm.openai().baseUrl(), llm.openai().apiKey(),
                    llm.openai().timeout(), true);
            if (!config.hasApiKey()) {
                log.warn("llm.provider=openai but no API key is configured; calls will fail with 401. "
                        + "Set LOOMVAULT_LLM_OPENAI_API_KEY or switch to loomvault.llm.provider=mock");
            }
            log.info("llm.provider=openai baseUrl={}", config.baseUrl());
            return new OpenAiCompatibleProvider(config);
        }
        MockOptions options = MockOptions.builder()
                .latency(llm.mock().minLatency().toMillis(), llm.mock().maxLatency().toMillis())
                .failFirstCalls(llm.mock().failFirstCalls())
                .build();
        log.info("llm.provider=mock latency={}..{}ms failFirstCalls={}",
                options.minLatencyMs(), options.maxLatencyMs(), options.failFirstCalls());
        return new DeterministicMockProvider(options);
    }

    private static boolean ping(StringRedisTemplate redis) {
        try {
            var connection = redis.getConnectionFactory().getConnection();
            String pong = connection.ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Engine
    // -----------------------------------------------------------------------------------------

    /**
     * The workflow catalogue. Returned empty and populated by the startup runner so that sample
     * loading failures are reported as application-health events rather than bean-creation errors.
     */
    @Bean
    public WorkflowRegistry workflowRegistry() {
        return new WorkflowRegistry();
    }

    @Bean
    public ToolRegistry toolRegistry(BuiltinTools builtinTools) {
        ToolRegistry registry = new ToolRegistry();
        builtinTools.registerInto(registry);
        log.info("tool.registry tools={}", registry.names());
        return registry;
    }

    @Bean(destroyMethod = "close")
    public ExecutionEngine executionEngine(LoomVaultProperties properties,
                                           WorkflowRegistry workflowRegistry,
                                           ExecutionRepository executionRepository,
                                           JournalStore journalStore,
                                           ExecutionEventBus eventBus,
                                           ToolRegistry toolRegistry,
                                           LlmGateway llmGateway,
                                           CostModel costModel) {
        LoomVaultProperties.Engine engine = properties.engine();
        EngineOptions options = EngineOptions.builder()
                .maxParallelNodes(engine.maxParallelNodes())
                .defaultMaxAttempts(engine.defaultMaxAttempts())
                .pollInterval(engine.pollInterval())
                .hardTimeoutSlack(engine.hardTimeoutSlack())
                .build();

        ExecutionEngine executionEngine = ExecutionEngine.builder()
                .workflows(workflowRegistry)
                .repository(executionRepository)
                .journalStore(journalStore)
                .eventPublisher(eventBus)
                .standardHandlers(toolRegistry)
                .llm(llmGateway)
                .costModel(costModel)
                .clock(Clock.systemUTC())
                .options(options)
                .build();
        log.info("engine.configured maxParallelNodes={} defaultMaxAttempts={} pollInterval={}",
                options.maxParallelNodes(), options.defaultMaxAttempts(), options.pollInterval());
        return executionEngine;
    }
}
