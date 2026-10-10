package io.loomvault.server.api;

import io.loomvault.core.engine.ExecutionEngine;
import io.loomvault.core.engine.ExecutionRequest;
import io.loomvault.core.engine.WorkflowRegistry;
import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.graph.WorkflowGraph;
import io.loomvault.core.journal.JournalEntry;
import io.loomvault.core.journal.JournalStore;
import io.loomvault.core.model.Execution;
import io.loomvault.core.model.NodeState;
import io.loomvault.core.port.ExecutionRepository;
import io.loomvault.core.util.Ids;
import io.loomvault.server.api.dto.ApprovalRequest;
import io.loomvault.server.api.dto.ExecutionView;
import io.loomvault.server.api.dto.StartExecutionRequest;
import io.loomvault.server.events.ExecutionEventBus;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Execution lifecycle over HTTP.
 *
 * <h3>Synchronous vs asynchronous start</h3>
 * <p>{@code POST /api/executions} returns as soon as the execution has been accepted, together
 * with its id ({@code 202 Accepted}). Pass {@code ?wait=true} to block until it finishes, parks on
 * an approval, or fails — that is the mode the smoke-test script and the {@code curl} examples
 * use, because one command that prints the final state is worth a lot during a demo.</p>
 *
 * <p>Executions are launched on virtual threads. A blocking engine call per HTTP request would
 * otherwise tie a platform thread to a model round-trip — the exact problem virtual threads exist
 * to solve.</p>
 */
@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);
    private static final int MAX_PAGE_SIZE = 200;
    private static final long WAIT_TIMEOUT_SECONDS = 180L;

    private final ExecutionEngine engine;
    private final ExecutionRepository repository;
    private final JournalStore journalStore;
    private final WorkflowRegistry workflows;
    private final ExecutionEventBus eventBus;
    private final ExecutorService launcher = Executors.newVirtualThreadPerTaskExecutor();

    public ExecutionController(ExecutionEngine engine,
                               ExecutionRepository repository,
                               JournalStore journalStore,
                               WorkflowRegistry workflows,
                               ExecutionEventBus eventBus) {
        this.engine = engine;
        this.repository = repository;
        this.journalStore = journalStore;
        this.workflows = workflows;
        this.eventBus = eventBus;
    }

    // -----------------------------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------------------------

    @GetMapping
    public List<ExecutionView> list(@RequestParam(defaultValue = "25") int limit) {
        int capped = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        return repository.findRecent(capped).stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public ExecutionView get(@PathVariable String id) {
        return toView(require(id));
    }

    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable String id) {
        Execution execution = require(id);
        return Map.of(
                "executionId", execution.id(),
                "workflowId", execution.workflowId(),
                "status", execution.status().name(),
                "terminal", execution.status().isTerminal(),
                "resumable", execution.status().isResumable(),
                "elapsedMs", execution.elapsedMillis(Instant.now()));
    }

    /** The raw append-only journal. This is the artefact that makes replay auditable. */
    @GetMapping("/{id}/journal")
    public List<JournalEntry> journal(@PathVariable String id,
                                      @RequestParam(defaultValue = "0") long fromSeq) {
        require(id);
        List<JournalEntry> entries = journalStore.readAll(id);
        return fromSeq <= 0 ? entries : entries.stream().filter(e -> e.seq() > fromSeq).toList();
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        require(id);
        return eventBus.subscribe(id);
    }

    /** Firehose stream across every execution — what the dashboard's live view listens to. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return eventBus.subscribeAll();
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

    @PostMapping
    public ResponseEntity<ExecutionView> start(@Valid @RequestBody StartExecutionRequest request,
                                               @RequestParam(defaultValue = "false") boolean wait) {
        String executionId = (request.executionId() == null || request.executionId().isBlank())
                ? Ids.executionId()
                : request.executionId();

        ExecutionRequest engineRequest = ExecutionRequest.builder()
                .executionId(executionId)
                .inputs(request.inputs())
                .metadata(request.metadata())
                .build();

        Future<Execution> future = launcher.submit(() -> engine.start(request.workflowId(), engineRequest));
        log.info("api.execution.start id={} workflow={} wait={}", executionId, request.workflowId(), wait);

        if (!wait) {
            return ResponseEntity.accepted().body(currentView(executionId));
        }
        try {
            return ResponseEntity.ok(toView(future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
        } catch (TimeoutException ex) {
            log.info("api.execution.wait.timeout id={} — returning current state", executionId);
            return ResponseEntity.ok(currentView(executionId));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity.ok(currentView(executionId));
        } catch (ExecutionException ex) {
            // Surface domain failures through the global handler with their proper status codes.
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new LoomVaultException(ErrorCode.INTERNAL_ERROR,
                    "failed to start execution " + executionId + ": " + ex.getMessage(), ex);
        }
    }

    @PostMapping("/{id}/resume")
    public ExecutionView resume(@PathVariable String id) {
        return toView(engine.resume(id));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        boolean accepted = engine.cancel(id);
        return Map.of("executionId", id, "cancelRequested", accepted,
                "status", currentView(id).status());
    }

    @PostMapping("/{id}/approve")
    public ExecutionView approve(@PathVariable String id,
                                 @RequestBody(required = false) ApprovalRequest request) {
        ApprovalRequest safe = request == null ? ApprovalRequest.of("unknown") : request;
        return toView(engine.approve(id, safe.nodeId(), safe.approver(), safe.comment()));
    }

    @PostMapping("/{id}/reject")
    public ExecutionView reject(@PathVariable String id,
                                @RequestBody(required = false) ApprovalRequest request) {
        ApprovalRequest safe = request == null ? ApprovalRequest.of("unknown") : request;
        return toView(engine.reject(id, safe.nodeId(), safe.approver(), safe.comment()));
    }

    // -----------------------------------------------------------------------------------------

    private Execution require(String executionId) {
        return repository.find(executionId).orElseThrow(() ->
                new LoomVaultException(ErrorCode.EXECUTION_NOT_FOUND, "unknown execution '" + executionId + "'"));
    }

    private ExecutionView toView(Execution execution) {
        WorkflowGraph graph = workflows.contains(execution.workflowId())
                ? workflows.graph(execution.workflowId())
                : null;
        Map<String, NodeState> states = repository.loadNodeStates(execution.id());
        return ExecutionView.of(execution, graph, states, engine.isActive(execution.id()), Instant.now());
    }

    /** View for an execution that may not be persisted yet (a just-accepted async start). */
    private ExecutionView currentView(String executionId) {
        return repository.find(executionId)
                .map(this::toView)
                .orElseGet(() -> ExecutionView.pending(executionId, "unknown", "1"));
    }
}
