package io.loomvault.server.startup;

import io.loomvault.core.engine.ExecutionEngine;
import io.loomvault.core.engine.ExecutionRequest;
import io.loomvault.core.engine.WorkflowRegistry;
import io.loomvault.core.json.LoomJson;
import io.loomvault.core.model.Execution;
import io.loomvault.core.model.WorkflowDefinition;
import io.loomvault.core.port.ExecutionRepository;
import io.loomvault.server.config.LoomVaultProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Startup sequence: load workflows, recover interrupted executions, optionally seed a demo run.
 *
 * <p>Order matters here. Recovery runs <em>after</em> the catalogue is loaded, because resuming an
 * execution needs its workflow definition to rebuild the graph. Getting this backwards produces
 * the least helpful failure mode imaginable: "recovery failed: unknown workflow" on every boot.</p>
 */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    /** YAML reader for workflow definitions; shares the engine's record conventions. */
    private static final com.fasterxml.jackson.databind.ObjectMapper yamlMapper =
            com.fasterxml.jackson.dataformat.yaml.YAMLMapper.builder()
                    .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    private final LoomVaultProperties properties;
    private final WorkflowRegistry workflows;
    private final ExecutionEngine engine;
    private final ExecutionRepository repository;

    public StartupRunner(LoomVaultProperties properties,
                         WorkflowRegistry workflows,
                         ExecutionEngine engine,
                         ExecutionRepository repository) {
        this.properties = properties;
        this.workflows = workflows;
        this.engine = engine;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadSampleWorkflows();
        recoverInterruptedExecutions();
        seedDemoExecution();
        printSummary();
    }

    // -----------------------------------------------------------------------------------------

    private void loadSampleWorkflows() {
        if (!properties.workflows().loadSamples()) {
            log.info("workflows.samples.disabled");
            return;
        }
        String location = properties.workflows().location();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
            if (resources.length == 0) {
                log.warn("workflows.samples.none location={}", location);
                return;
            }
            for (Resource resource : Arrays.stream(resources)
                    .sorted(java.util.Comparator.comparing(r -> String.valueOf(r.getFilename())))
                    .toList()) {
                loadOne(resource);
            }
        } catch (IOException ex) {
            log.error("workflows.samples.failed location={} reason={}", location, ex.getMessage());
        }
    }

    private void loadOne(Resource resource) {
        String filename = String.valueOf(resource.getFilename()).toLowerCase(java.util.Locale.ROOT);
        try (InputStream in = resource.getInputStream()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            WorkflowDefinition definition = readDefinition(filename, raw);
            workflows.register(definition);
            log.info("workflow.loaded id={} nodes={} edges={}",
                    definition.id(), definition.nodes().size(), definition.edges().size());
        } catch (IOException ex) {
            // A broken sample must not stop the application: an operator may still want the API
            // to come up so they can upload a corrected definition.
            log.error("workflow.load.failed file={} reason={}", resource.getFilename(), ex.getMessage());
        }
    }

    /**
     * YAML is the preferred authoring format (multi-line prompts stay readable); JSON is accepted
     * so that tools emitting JSON can round-trip definitions without a conversion step.
     */
    private WorkflowDefinition readDefinition(String filename, String raw) throws IOException {
        if (filename.endsWith(".yaml") || filename.endsWith(".yml")) {
            return yamlMapper.readValue(raw, WorkflowDefinition.class);
        }
        return LoomJson.mapper().readValue(raw, WorkflowDefinition.class);
    }

    private void recoverInterruptedExecutions() {
        if (!properties.engine().recoverOnStartup()) {
            log.info("engine.recovery.disabled");
            return;
        }
        List<Execution> recovered = engine.recoverIncomplete();
        if (!recovered.isEmpty()) {
            log.info("engine.recovery.recovered count={} ids={}", recovered.size(),
                    recovered.stream().map(Execution::id).toList());
        }
    }

    /**
     * Runs the demo workflow once on a fresh database, so that opening the UI shows a complete
     * execution trace instead of an empty state. Skipped when any execution already exists, which
     * keeps restarts from piling up duplicates.
     */
    private void seedDemoExecution() {
        if (!properties.workflows().runDemoOnStartup()) {
            return;
        }
        if (repository.count() > 0) {
            log.debug("demo.seed.skipped reason=existing-executions");
            return;
        }
        String workflowId = properties.workflows().demoWorkflowId();
        if (!workflows.contains(workflowId)) {
            log.debug("demo.seed.skipped reason=workflow-not-registered id={}", workflowId);
            return;
        }
        Map<String, Object> inputs = Map.of("ticketId", properties.workflows().demoInputTicket());
        // Deliberately asynchronous: the HTTP port should open immediately, and the demo run is
        // just as useful a second later.
        CompletableFuture.runAsync(() -> {
            try {
                Execution execution = engine.start(workflowId,
                        ExecutionRequest.builder().inputs(inputs).metadata(Map.of("seeded", "true")).build());
                log.info("demo.seed.finished id={} status={}", execution.id(), execution.status());
            } catch (RuntimeException ex) {
                log.warn("demo.seed.failed workflow={} reason={}", workflowId, ex.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
        log.info("demo.seed.started workflow={} inputs={}", workflowId, inputs);
    }

    private void printSummary() {
        log.info("""

                        LoomVault is ready.
                          workflows : {}
                          tools     : {}
                          endpoints : http://localhost:{}/  (control room)
                                      http://localhost:{}/api/system/info
                                      http://localhost:{}/actuator/prometheus
                        """,
                workflows.all().stream().map(WorkflowDefinition::id).toList(),
                engine.activeExecutionIds().isEmpty() ? "idle" : "busy",
                port(), port(), port());
    }

    private String port() {
        return System.getProperty("server.port",
                System.getenv().getOrDefault("SERVER_PORT", "8080"));
    }
}
