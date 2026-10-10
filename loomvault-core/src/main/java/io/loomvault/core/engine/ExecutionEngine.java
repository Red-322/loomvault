package io.loomvault.core.engine;

import io.loomvault.core.budget.BudgetDecision;
import io.loomvault.core.budget.BudgetLedger;
import io.loomvault.core.budget.CostModel;
import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LlmException;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.expr.Expressions;
import io.loomvault.core.graph.WorkflowGraph;
import io.loomvault.core.journal.JournalEntry;
import io.loomvault.core.journal.JournalEntryType;
import io.loomvault.core.journal.JournalStore;
import io.loomvault.core.journal.ReplayIndex;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.model.BudgetStrategy;
import io.loomvault.core.model.EdgeDefinition;
import io.loomvault.core.model.Execution;
import io.loomvault.core.model.ExecutionStatus;
import io.loomvault.core.model.NodeDefinition;
import io.loomvault.core.model.NodeState;
import io.loomvault.core.model.NodeStatus;
import io.loomvault.core.model.RetryPolicy;
import io.loomvault.core.model.Usage;
import io.loomvault.core.port.EventPublisher;
import io.loomvault.core.port.ExecutionEvent;
import io.loomvault.core.port.ExecutionRepository;
import io.loomvault.core.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The durable execution engine.
 *
 * <h2>What it guarantees</h2>
 * <ol>
 *   <li><b>Durability.</b> Every state change is appended to the journal before it becomes
 *       visible to subscribers, so a crash loses at most the in-flight attempts — never
 *       completed work.</li>
 *   <li><b>Resumability.</b> {@link #resume(String)} rebuilds the scheduling state from the
 *       journal: completed nodes keep their recorded outputs and are <em>not</em> re-executed,
 *       so their side effects are not repeated.</li>
 *   <li><b>Budget enforcement.</b> Token, cost, call-count and wall-clock limits are evaluated
 *       by the scheduler, and the configured {@link BudgetStrategy} decides whether to stop,
 *       degrade or merely warn.</li>
 * </ol>
 *
 * <h2>Concurrency model — the invariant that keeps this simple</h2>
 * <p>Exactly one scheduler thread (the caller of {@code drive}) owns all mutable scheduling
 * state: the ready queue, predecessor counters, node states, the in-flight map and the execution
 * record. Worker threads run node handlers against <em>immutable snapshots</em> (a
 * {@link NodeContext}) and communicate back through a single {@link BlockingQueue}.</p>
 * <p>The only cross-thread mutable field is {@code cancelled}, a volatile boolean. There is no
 * lock ordering to get wrong and no lock-free algorithm to reason about — parallelism comes from
 * virtual threads fanning out the IO-bound model calls, which is exactly where it pays.</p>
 *
 * <h2>Replay semantics</h2>
 * <p>A node recorded as completed is restored from the journal, never re-executed. A node whose
 * {@code NODE_STARTED} has no matching terminal entry was interrupted mid-flight: LoomVault
 * records a recovery warning and re-executes it, because the alternative — pretending to know
 * whether the side effect landed — is worse. Nodes that must not be re-executed belong behind an
 * idempotency key in the tool implementation.</p>
 */
public final class ExecutionEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(200);

    private final WorkflowRegistry workflows;
    private final ExecutionRepository repository;
    private final JournalStore journal;
    private final EventPublisher events;
    private final NodeHandlerRegistry handlers;
    private final LlmGateway llm;
    private final CostModel costModel;
    private final Clock clock;
    private final EngineOptions options;
    private final ExecutorService workers;

    /** Executions currently being driven by this engine instance. */
    private final ConcurrentMap<String, ExecutionRun> active = new ConcurrentHashMap<>();

    private ExecutionEngine(Builder builder) {
        this.workflows = builder.workflows;
        this.repository = builder.repository;
        this.journal = builder.journalStore;
        this.events = builder.eventPublisher;
        this.handlers = builder.handlers;
        this.llm = builder.llm;
        this.costModel = builder.costModel;
        this.clock = builder.clock;
        this.options = builder.options;
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
    }

    public static Builder builder() {
        return new Builder();
    }

    // =========================================================================================
    // Public API
    // =========================================================================================

    /**
     * Starts a new execution and drives it to a terminal or parked state.
     *
     * <p>Blocks until the execution finishes, parks on an approval, or fails. If
     * {@code request.executionId()} already exists, the existing execution is returned unchanged —
     * a retried POST is therefore safe.</p>
     */
    public Execution start(String workflowId, ExecutionRequest request) {
        WorkflowGraph graph = workflows.graph(workflowId);
        String executionId = (request.executionId() == null || request.executionId().isBlank())
                ? Ids.executionId()
                : request.executionId();

        Optional<Execution> existing = repository.find(executionId);
        if (existing.isPresent()) {
            log.debug("execution.start.idempotent id={}", executionId);
            return existing.get();
        }

        Instant now = clock.instant();
        Execution execution = Execution
                .pending(executionId, workflowId, graph.definition().version(), request.inputs(), now)
                .withStatus(ExecutionStatus.RUNNING)
                .withStartedAt(now);
        // Revision management belongs to the repository: create() persists the initial value, and
        // every save() bumps it. The engine must never touch it by hand.
        execution = repository.create(execution);

        BudgetLedger budget = new BudgetLedger("workflow", graph.definition().budget(), clock);
        ExecutionRun run = new ExecutionRun(graph, execution, ReplayIndex.empty(), budget);

        append(run, JournalEntryType.EXECUTION_STARTED, null, buildStartPayload(graph, execution, request));
        // Same hydration path as resume: with an empty replay index this simply queues the roots.
        rehydrate(run);
        log.info("execution.started id={} workflow={} nodes={}",
                executionId, workflowId, graph.nodes().size());

        return drive(run);
    }

    /**
     * Resumes an execution from its journal: completed nodes are restored (not re-run), the rest
     * continue from where the previous process stopped.
     *
     * @throws LoomVaultException {@code EXECUTION_NOT_FOUND} / {@code EXECUTION_NOT_RESUMABLE}
     */
    public Execution resume(String executionId) {
        Execution execution = requireExecution(executionId);
        if (execution.status().isTerminal()) {
            throw new LoomVaultException(ErrorCode.EXECUTION_NOT_RESUMABLE,
                    "execution " + executionId + " is " + execution.status() + " and cannot be resumed");
        }

        WorkflowGraph graph = workflows.graph(execution.workflowId());
        List<JournalEntry> entries = journal.readAll(executionId);
        ReplayIndex replay = ReplayIndex.from(entries);

        Instant now = clock.instant();
        Execution resuming = execution
                .withStatus(ExecutionStatus.RUNNING)
                .withResumeCount(execution.resumeCount() + 1)
                .withStartedAt(execution.startedAt() == null ? now : execution.startedAt());
        resuming = repository.save(resuming);

        BudgetLedger budget = BudgetLedger.resuming("workflow", graph.definition().budget(), clock,
                replay.recordedUsage(), resuming.startedAt());

        ExecutionRun run = new ExecutionRun(graph, resuming, replay, budget);
        run.nodeStates.putAll(repository.loadNodeStates(executionId));
        warnOnInterruptedNodes(run);
        rehydrate(run);

        append(run, JournalEntryType.EXECUTION_RESUMED, null, Map.of(
                "replayedEntries", entries.size(),
                "restoredNodes", List.copyOf(replay.completedOutputs().keySet()),
                "interruptedNodes", List.copyOf(replay.interruptedNodes())));
        log.info("execution.resumed id={} replayedEntries={} restored={}",
                executionId, entries.size(), replay.completedOutputs().size());

        return drive(run);
    }

    /**
     * Approves a parked approval node and continues the execution.
     *
     * @param nodeId the approval node to grant; when {@code null} the first pending one is used
     */
    public Execution approve(String executionId, String nodeId, String approver, String comment) {
        Execution execution = requireExecution(executionId);
        if (execution.status() != ExecutionStatus.WAITING_APPROVAL) {
            throw new LoomVaultException(ErrorCode.EXECUTION_NOT_RESUMABLE,
                    "execution " + executionId + " is not waiting for approval (status=" + execution.status() + ")");
        }
        ReplayIndex replay = ReplayIndex.from(journal.readAll(executionId));
        String target = resolveApprovalNode(nodeId, replay);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approved", true);
        output.put("approver", approver == null ? "unknown" : approver);
        output.put("comment", comment == null ? "" : comment);
        output.put("decidedAt", clock.instant().toString());

        appendRaw(executionId, execution.workflowId(), JournalEntryType.APPROVAL_GRANTED, target,
                Map.of("output", output,
                        "approver", output.get("approver"),
                        "comment", output.get("comment")));
        log.info("approval.granted execution={} node={} approver={}", executionId, target, approver);

        return resume(executionId);
    }

    /** Rejects a parked approval node; the execution terminates as {@code FAILED}. */
    public Execution reject(String executionId, String nodeId, String approver, String comment) {
        Execution execution = requireExecution(executionId);
        if (execution.status() != ExecutionStatus.WAITING_APPROVAL) {
            throw new LoomVaultException(ErrorCode.EXECUTION_NOT_RESUMABLE,
                    "execution " + executionId + " is not waiting for approval (status=" + execution.status() + ")");
        }
        ReplayIndex replay = ReplayIndex.from(journal.readAll(executionId));
        String target = resolveApprovalNode(nodeId, replay);

        String reason = "approval rejected on node '" + target + "' by "
                + (approver == null ? "unknown" : approver);
        appendRaw(executionId, execution.workflowId(), JournalEntryType.APPROVAL_REJECTED, target,
                Map.of("approver", approver == null ? "unknown" : approver,
                        "comment", comment == null ? "" : comment,
                        "reason", reason));
        log.info("approval.rejected execution={} node={} approver={}", executionId, target, approver);

        return repository.save(execution
                .withStatus(ExecutionStatus.FAILED)
                .withFailure(ErrorCode.APPROVAL_REJECTED.name(), reason)
                .withFinishedAt(clock.instant()));
    }

    /**
     * Requests cancellation.
     *
     * @return {@code true} when the execution was active or parked and is now cancelled or flagged
     *         for cancellation; {@code false} when it had already finished
     */
    public boolean cancel(String executionId) {
        ExecutionRun run = active.get(executionId);
        if (run != null) {
            run.cancelled = true;
            log.info("execution.cancel.requested id={}", executionId);
            return true;
        }
        Execution execution = requireExecution(executionId);
        if (execution.isTerminal()) {
            return false;
        }
        appendRaw(executionId, execution.workflowId(), JournalEntryType.EXECUTION_STOPPED, null,
                Map.of("reason", "cancelled by operator", "status", ExecutionStatus.CANCELLED.name()));
        repository.save(execution
                .withStatus(ExecutionStatus.CANCELLED)
                .withFailure(ErrorCode.EXECUTION_CANCELLED.name(), "cancelled by operator")
                .withFinishedAt(clock.instant()));
        return true;
    }

    /**
     * Recovers executions that a previous process left {@code RUNNING}.
     *
     * <p>Intended to run once at application startup. Executions parked in
     * {@code WAITING_APPROVAL} are intentionally skipped: they are not broken, they are waiting for
     * a human, and restarting them would be wrong.</p>
     *
     * @return the executions that were recovered successfully
     */
    public List<Execution> recoverIncomplete() {
        List<Execution> candidates = repository.findByStatus(List.of(ExecutionStatus.RUNNING), 200);
        List<Execution> recovered = new ArrayList<>();
        for (Execution candidate : candidates) {
            try {
                recovered.add(resume(candidate.id()));
            } catch (RuntimeException ex) {
                log.warn("execution.recovery.failed id={} reason={}", candidate.id(), ex.getMessage());
            }
        }
        if (!recovered.isEmpty()) {
            log.info("execution.recovery.completed count={}", recovered.size());
        }
        return recovered;
    }

    public Optional<Execution> find(String executionId) {
        return repository.find(executionId);
    }

    public boolean isActive(String executionId) {
        return active.containsKey(executionId);
    }

    public Set<String> activeExecutionIds() {
        return Set.copyOf(active.keySet());
    }

    /** Waits until the execution reaches a terminal state or {@code timeout} elapses. */
    public Optional<Execution> awaitTerminal(String executionId, Duration timeout) {
        Instant deadline = clock.instant().plus(timeout);
        while (true) {
            Optional<Execution> current = repository.find(executionId);
            if (current.isEmpty() || current.get().isTerminal() || !clock.instant().isBefore(deadline)) {
                return current;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return repository.find(executionId);
            }
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    // =========================================================================================
    // Scheduler
    // =========================================================================================

    private Execution drive(ExecutionRun run) {
        String executionId = run.execution.id();
        if (active.putIfAbsent(executionId, run) != null) {
            throw new LoomVaultException(ErrorCode.CONCURRENCY_CONFLICT,
                    "execution " + executionId + " is already being driven by this engine instance");
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable("executionId", executionId)) {
            return driveLoop(run);
        } finally {
            active.remove(executionId, run);
        }
    }

    private Execution driveLoop(ExecutionRun run) {
        while (true) {
            if (run.cancelled) {
                return stop(run, ExecutionStatus.CANCELLED, ErrorCode.EXECUTION_CANCELLED,
                        "cancelled by operator");
            }
            if (run.abortReason != null) {
                return stop(run, ExecutionStatus.FAILED, run.abortReason.code, run.abortReason.message);
            }

            // ---- budget gate ---------------------------------------------------------------
            BudgetDecision budget = run.budget.evaluate();
            if (budget.isExceeded()) {
                if (budget.policy().onExceed() == BudgetStrategy.FAIL) {
                    append(run, JournalEntryType.BUDGET_EXCEEDED, null,
                            Map.of("detail", budget.describe(), "usage", usageMap(budget.usage())));
                    return stop(run, ExecutionStatus.BUDGET_EXCEEDED, ErrorCode.BUDGET_EXCEEDED,
                            budget.describe());
                }
                // Non-FAIL strategies keep the execution alive; record the crossing once and
                // steer handlers towards cheaper models from here on.
                if (!run.degradeAnnounced) {
                    run.degradeAnnounced = true;
                    append(run, JournalEntryType.BUDGET_EXCEEDED, null, Map.of(
                            "detail", budget.describe(),
                            "strategy", budget.policy().onExceed().name(),
                            "action", "continuing under " + budget.policy().onExceed()));
                    log.warn("execution.budget.degraded id={} detail={}", run.execution.id(), budget.describe());
                }
                run.degraded = true;
            } else {
                run.degraded = false;
                if (budget.isWarning() && run.budget.claimWarning()) {
                    append(run, JournalEntryType.BUDGET_WARNING, null,
                            Map.of("detail", budget.describe(), "usage", usageMap(budget.usage())));
                    log.warn("execution.budget.warning id={} detail={}", run.execution.id(), budget.describe());
                }
            }

            submitReady(run);

            if (run.inFlight.isEmpty() && run.ready.isEmpty()) {
                return settle(run);
            }

            NodeCompletion completion;
            try {
                completion = run.completions.poll(options.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return stop(run, ExecutionStatus.CANCELLED, ErrorCode.EXECUTION_CANCELLED,
                        "scheduler interrupted");
            }
            if (completion != null) {
                complete(run, completion);
            }
        }
    }

    /** Queues ready nodes for execution, honouring the parallelism cap. */
    private void submitReady(ExecutionRun run) {
        while (!run.ready.isEmpty()) {
            if (run.inFlight.size() >= options.maxParallelNodes()) {
                return;
            }
            String nodeId = run.ready.poll();
            NodeState current = run.nodeStates.get(nodeId);
            if (current != null && current.status().isTerminal()) {
                continue;
            }
            if (run.inFlight.containsKey(nodeId)) {
                continue;
            }
            NodeDefinition node = run.graph.node(nodeId);
            append(run, JournalEntryType.NODE_SCHEDULED, nodeId, Map.of("node", node.name()));
            persistNodeState(run, NodeState.pending(nodeId).withStatus(NodeStatus.READY));

            int initialAttempt = run.attemptView().getOrDefault(nodeId, 0);
            CompletableFuture<NodeOutcome> future = CompletableFuture
                    .supplyAsync(() -> executeWithRetry(run, node, initialAttempt), workers)
                    // Watchdog: a handler that ignores interruption cannot pin a slot forever.
                    .orTimeout(hardTimeoutMillis(node), TimeUnit.MILLISECONDS)
                    .handle((outcome, error) -> error == null
                            ? outcome
                            : NodeOutcome.failed(ErrorCode.NODE_TIMEOUT,
                            "node exceeded its hard time budget: " + rootMessage(error), false));
            future.whenComplete((outcome, error) -> run.completions.add(new NodeCompletion(nodeId,
                    outcome == null
                            ? NodeOutcome.failed(ErrorCode.INTERNAL_ERROR, "handler produced no outcome", false)
                            : outcome)));
            run.inFlight.put(nodeId, future);
        }
    }

    /** Retry loop for a single node. Runs on a virtual thread. */
    private NodeOutcome executeWithRetry(ExecutionRun run, NodeDefinition node, int initialAttempt) {
        // An undeclared policy falls back to the engine default; an explicit `maxAttempts: 1`
        // stays "never retry". Conflating those two was a real bug once — see ARCHITECTURE.md.
        RetryPolicy retry = node.effectiveRetry(options.defaultMaxAttempts());
        NodeHandler handler = handlers.require(node.kind());
        int attempt = Math.max(0, initialAttempt);

        try (MDC.MDCCloseable ignored = MDC.putCloseable("nodeId", node.id())) {
            while (true) {
                if (run.cancelled) {
                    return NodeOutcome.failed(ErrorCode.EXECUTION_CANCELLED, "cancelled while running",
                            false, attempt);
                }
                attempt++;
                append(run, JournalEntryType.NODE_STARTED, node.id(), Map.of("attempt", attempt));
                persistNodeState(run, NodeState.pending(node.id()).running(attempt, clock.instant()));

                try {
                    NodeResult result = handler.execute(buildInvocation(run, node, attempt));
                    if (result.awaitingApproval()) {
                        return NodeOutcome.awaiting(result.approvalRequest(), attempt);
                    }
                    return NodeOutcome.completed(result.output(), result.usage(), attempt);
                } catch (Throwable failure) {
                    String message = rootMessage(failure);
                    boolean retryable = isRetryable(failure);
                    append(run, JournalEntryType.NODE_FAILED, node.id(), Map.of(
                            "attempt", attempt,
                            "error", message,
                            "errorType", failure.getClass().getName(),
                            "retryable", retryable));
                    persistNodeState(run, NodeState.pending(node.id()).running(attempt, clock.instant())
                            .failed(ErrorCode.NODE_FAILED.name(), message, clock.instant()));

                    if (!retryable || attempt >= retry.maxAttempts()) {
                        return NodeOutcome.failed(ErrorCode.NODE_FAILED, message, retryable, attempt);
                    }
                    Duration backoff = retry.backoffFor(attempt);
                    if (!backoff.isZero()) {
                        append(run, JournalEntryType.NODE_RETRY_SCHEDULED, node.id(),
                                Map.of("attempt", attempt, "delayMs", backoff.toMillis()));
                        persistNodeState(run, NodeState.pending(node.id()).running(attempt, clock.instant())
                                .withStatus(NodeStatus.RETRYING));
                        if (!sleepInterruptibly(backoff)) {
                            return NodeOutcome.failed(ErrorCode.EXECUTION_CANCELLED,
                                    "cancelled during backoff", false, attempt);
                        }
                    }
                }
            }
        }
    }

    // =========================================================================================
    // State transitions (all on the scheduler thread)
    // =========================================================================================

    private void complete(ExecutionRun run, NodeCompletion completion) {
        run.inFlight.remove(completion.nodeId());
        // Remember how many attempts were burned so a later resume does not restart the count.
        run.replayAttempts.merge(completion.nodeId(), completion.outcome.attempts, Math::max);

        NodeDefinition node = run.graph.node(completion.nodeId());
        NodeOutcome outcome = completion.outcome;
        NodeState state = run.nodeStates.getOrDefault(completion.nodeId(), NodeState.pending(completion.nodeId()));

        switch (outcome.status) {
            case COMPLETED -> {
                Map<String, Object> output = outcome.output;
                run.outputs.put(node.id(), output);
                run.budget.record(outcome.usage);
                persistNodeState(run, state.completed(output, outcome.usage, clock.instant()));
                append(run, JournalEntryType.NODE_COMPLETED, node.id(), Map.of(
                        "output", output,
                        "usage", usageMap(outcome.usage),
                        "attempt", Math.max(1, outcome.attempts),
                        "cacheHit", outcome.usage.cacheHits() > 0));
                if (outcome.usage.cacheHits() > 0) {
                    append(run, JournalEntryType.CACHE_HIT, node.id(),
                            Map.of("model", node.stringConfig("model", "unknown")));
                }
                propagate(run, node.id(), true);
            }
            case AWAIT_APPROVAL -> {
                persistNodeState(run, state.awaitingApproval(clock.instant()));
                append(run, JournalEntryType.APPROVAL_REQUESTED, node.id(), outcome.approvalRequest);
                log.info("execution.waiting_approval id={} node={}", run.execution.id(), node.id());
            }
            case FAILED -> {
                persistNodeState(run, state.failed(outcome.errorCode.name(), outcome.errorMessage, clock.instant()));
                if (node.optional()) {
                    log.warn("execution.node.optional_failure id={} node={} error={}",
                            run.execution.id(), node.id(), outcome.errorMessage);
                    // An optional node that failed releases its successors as "branch not taken".
                    propagate(run, node.id(), false);
                } else {
                    run.abortReason = new AbortReason(outcome.errorCode,
                            "node '" + node.id() + "' failed: " + outcome.errorMessage);
                }
            }
        }
    }

    /**
     * Releases the outgoing edges of a settled node.
     *
     * <p>Each edge contributes a "settled" predecessor, and — when its condition holds — also a
     * "satisfied" one. A node becomes ready when every incoming edge has settled and at least one
     * was satisfied. When all settled and none satisfied, the branch was not taken: the node is
     * skipped, and the skip propagates down the graph.</p>
     */
    private void propagate(ExecutionRun run, String fromNodeId, boolean sourceSucceeded) {
        for (EdgeDefinition edge : run.graph.outgoing(fromNodeId)) {
            String target = edge.to();
            int settled = run.settled.merge(target, 1, Integer::sum);

            boolean taken = sourceSucceeded && (!edge.hasCondition() || testCondition(run, edge.condition()));
            if (taken) {
                run.satisfied.merge(target, 1, Integer::sum);
            }

            if (settled >= run.graph.predecessorCount(target)) {
                if (run.satisfied.getOrDefault(target, 0) > 0) {
                    enqueue(run, target);
                } else {
                    skipNode(run, target, "no incoming branch was taken");
                }
            }
        }
    }

    private void skipNode(ExecutionRun run, String nodeId, String reason) {
        NodeState current = run.nodeStates.getOrDefault(nodeId, NodeState.pending(nodeId));
        if (current.status().isTerminal()) {
            return;
        }
        persistNodeState(run, current.skipped(reason));
        append(run, JournalEntryType.NODE_SKIPPED, nodeId, Map.of("reason", reason));
        propagate(run, nodeId, false);
    }

    /** Adds a node to the ready queue at most once per pass. */
    private void enqueue(ExecutionRun run, String nodeId) {
        NodeState current = run.nodeStates.get(nodeId);
        if (current != null && current.status().isTerminal()) {
            return;
        }
        if (run.enqueued.add(nodeId)) {
            run.ready.add(nodeId);
        }
    }

    /** Called when nothing is running and nothing is ready: decide how the execution ends. */
    private Execution settle(ExecutionRun run) {
        boolean awaitingApproval = run.nodeStates.values().stream()
                .anyMatch(s -> s.status() == NodeStatus.WAITING_APPROVAL);
        if (awaitingApproval) {
            return park(run);
        }

        // Anything that never settled was unreachable: mark it so the UI shows intent, not a hang.
        for (String nodeId : run.graph.nodeIds()) {
            NodeState state = run.nodeStates.get(nodeId);
            if (state == null || !state.status().isTerminal()) {
                NodeState skipped = (state == null ? NodeState.pending(nodeId) : state)
                        .skipped("not reached before the execution drained");
                persistNodeState(run, skipped);
                append(run, JournalEntryType.NODE_SKIPPED, nodeId, Map.of("reason", "not reached"));
            }
        }
        return succeed(run);
    }

    private Execution succeed(ExecutionRun run) {
        Map<String, Object> finals = new LinkedHashMap<>();
        for (String nodeId : run.graph.nodeIds()) {
            if (run.graph.outgoing(nodeId).isEmpty() && run.outputs.containsKey(nodeId)) {
                finals.put(nodeId, run.outputs.get(nodeId));
            }
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("nodes", new LinkedHashMap<>(run.outputs));
        outputs.put("final", finals);

        Execution updated = run.execution
                .withStatus(ExecutionStatus.SUCCEEDED)
                .withOutputs(outputs)
                .withUsage(run.budget.snapshot())
                .withFinishedAt(clock.instant());
        updated = repository.save(updated);
        run.execution = updated;

        append(run, JournalEntryType.EXECUTION_FINISHED, null,
                Map.of("outputs", finals, "usage", usageMap(run.budget.snapshot())));
        log.info("execution.succeeded id={} nodes={} tokens={} costUsd={}",
                updated.id(), run.outputs.size(), run.budget.snapshot().totalTokens(),
                run.budget.snapshot().costUsd().toPlainString());
        return updated;
    }

    private Execution park(ExecutionRun run) {
        Execution updated = run.execution
                .withStatus(ExecutionStatus.WAITING_APPROVAL)
                .withUsage(run.budget.snapshot());
        updated = repository.save(updated);
        run.execution = updated;
        log.info("execution.parked id={} waiting for approval", updated.id());
        return updated;
    }

    private Execution stop(ExecutionRun run, ExecutionStatus status, ErrorCode code, String message) {
        for (String nodeId : run.graph.nodeIds()) {
            NodeState state = run.nodeStates.get(nodeId);
            if (state == null || !state.status().isTerminal()) {
                persistNodeState(run, (state == null ? NodeState.pending(nodeId) : state)
                        .skipped("execution " + status.name().toLowerCase() + " before this node ran"));
            }
        }
        Execution updated = run.execution
                .withStatus(status)
                .withFailure(code.name(), message)
                .withUsage(run.budget.snapshot())
                .withFinishedAt(clock.instant());
        updated = repository.save(updated);
        run.execution = updated;

        append(run, JournalEntryType.EXECUTION_STOPPED, null,
                Map.of("status", status.name(), "code", code.name(), "reason", message,
                        "usage", usageMap(run.budget.snapshot())));
        log.info("execution.stopped id={} status={} reason={}", updated.id(), status, message);
        return updated;
    }

    // =========================================================================================
    // Rehydration (cold start)
    // =========================================================================================

    /**
     * Rebuilds the in-memory scheduling state from the journal.
     *
     * <p>The topological order is what makes this a single pass: when a node is visited, every
     * predecessor has already been classified, so its predecessor counters are final.</p>
     */
    private void rehydrate(ExecutionRun run) {
        for (String nodeId : run.graph.topologicalOrder()) {
            NodeState persisted = run.nodeStates.get(nodeId);
            if (persisted != null && persisted.status().isTerminal()) {
                if (persisted.status() == NodeStatus.COMPLETED) {
                    run.outputs.put(nodeId, persisted.output());
                }
                primeCounters(run, nodeId, persisted.status() == NodeStatus.COMPLETED);
                continue;
            }

            if (run.replay.isCompleted(nodeId)) {
                Map<String, Object> output = run.replay.outputOf(nodeId);
                run.outputs.put(nodeId, output);
                persistNodeState(run, new NodeState(nodeId, NodeStatus.COMPLETED,
                        Math.max(1, run.replay.attemptOf(nodeId)), output, null, null, null, null,
                        run.replay.nodeUsageOf(nodeId)));
                primeCounters(run, nodeId, true);
                continue;
            }
            if (run.replay.wasRejected(nodeId)) {
                persistNodeState(run, NodeState.pending(nodeId)
                        .failed(ErrorCode.APPROVAL_REJECTED.name(), "rejected before this resume", clock.instant()));
                primeCounters(run, nodeId, false);
                continue;
            }
            if (run.replay.pendingApprovals().contains(nodeId)) {
                persistNodeState(run, NodeState.pending(nodeId).awaitingApproval(clock.instant()));
                continue;
            }

            int settled = run.settled.getOrDefault(nodeId, 0);
            int predecessors = run.graph.predecessorCount(nodeId);
            if (settled >= predecessors) {
                if (run.satisfied.getOrDefault(nodeId, 0) > 0 || predecessors == 0) {
                    enqueue(run, nodeId);
                } else {
                    skipNode(run, nodeId, "no incoming branch was taken (recovered)");
                }
            }
        }
    }

    /**
     * Replays the counter effect of an already-settled node. Only called during rehydration,
     * where the graph is walked in topological order and no side effects are needed.
     */
    private void primeCounters(ExecutionRun run, String nodeId, boolean success) {
        for (EdgeDefinition edge : run.graph.outgoing(nodeId)) {
            String target = edge.to();
            run.settled.merge(target, 1, Integer::sum);
            boolean taken = success && (!edge.hasCondition() || testCondition(run, edge.condition()));
            if (taken) {
                run.satisfied.merge(target, 1, Integer::sum);
            }
        }
    }

    /**
     * Emits a recovery warning for nodes that opened a side-effect window and never closed it.
     * The engine still re-executes them (see class javadoc); the warning makes the risk visible.
     */
    private void warnOnInterruptedNodes(ExecutionRun run) {
        for (String nodeId : run.replay.interruptedNodes()) {
            if (!run.graph.contains(nodeId)) {
                continue;
            }
            NodeDefinition node = run.graph.node(nodeId);
            if (!node.idempotent()) {
                log.warn("execution.recovery.non_idempotent id={} node={} — re-executing a node that was "
                                + "interrupted mid-flight; ensure it is guarded by an idempotency key",
                        run.execution.id(), nodeId);
                append(run, JournalEntryType.RECOVERY_WARNING, nodeId,
                        Map.of("reason", "non-idempotent node was interrupted and will be re-executed",
                                "nodeName", node.name()));
            }
        }
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private NodeInvocation buildInvocation(ExecutionRun run, NodeDefinition node, int attempt) {
        NodeContext context = NodeContext.builder()
                .executionId(run.execution.id())
                .nodeId(node.id())
                .inputs(run.execution.inputs())
                .nodeOutputs(run.snapshot())
                .llm(llm)
                .costModel(costModel)
                .clock(clock)
                .budget(run.budget)
                .attempts(run.attemptView())
                .degraded(run.degraded)
                .build();
        return new NodeInvocation(run.execution.id(), node.id(), node.kind(), node.config(),
                attempt, clock.instant(), context);
    }

    private boolean testCondition(ExecutionRun run, String condition) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("inputs", run.execution.inputs());
        variables.put("nodes", run.outputs);
        return Expressions.evaluateBoolean(condition, variables);
    }

    private void persistNodeState(ExecutionRun run, NodeState state) {
        run.nodeStates.put(state.nodeId(), state);
        repository.saveNodeState(run.execution.id(), state);
    }

    private void append(ExecutionRun run, JournalEntryType type, String nodeId, Map<String, Object> payload) {
        JournalEntry entry = journal.append(run.execution.id(), type, nodeId, payload, clock.instant());
        emit(ExecutionEvent.from(entry, run.execution.workflowId()));
    }

    private void appendRaw(String executionId, String workflowId, JournalEntryType type, String nodeId,
                           Map<String, Object> payload) {
        JournalEntry entry = journal.append(executionId, type, nodeId, payload, clock.instant());
        emit(ExecutionEvent.from(entry, workflowId));
    }

    private void emit(ExecutionEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException ex) {
            // A broken subscriber must never fail an execution.
            log.debug("event.publish.failed type={} reason={}", event.type(), ex.getMessage());
        }
    }

    private Execution requireExecution(String executionId) {
        return repository.find(executionId).orElseThrow(() ->
                new LoomVaultException(ErrorCode.EXECUTION_NOT_FOUND, "unknown execution '" + executionId + "'"));
    }

    private String resolveApprovalNode(String requested, ReplayIndex replay) {
        if (requested != null && !requested.isBlank()) {
            if (!replay.pendingApprovals().contains(requested)) {
                throw new LoomVaultException(ErrorCode.APPROVAL_REQUIRED,
                        "node '" + requested + "' is not waiting for approval; pending: " + replay.pendingApprovals());
            }
            return requested;
        }
        return replay.firstPendingApproval().orElseThrow(() ->
                new LoomVaultException(ErrorCode.APPROVAL_REQUIRED, "no approval node is pending"));
    }

    private Map<String, Object> buildStartPayload(WorkflowGraph graph, Execution execution,
                                                  ExecutionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowId", execution.workflowId());
        payload.put("workflowVersion", execution.workflowVersion());
        payload.put("inputs", execution.inputs());
        payload.put("metadata", request.metadata());
        payload.put("nodes", List.copyOf(graph.nodeIds()));
        payload.put("budget", graph.definition().budget().asMap());
        return payload;
    }

    private static Map<String, Object> usageMap(Usage usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inputTokens", usage.inputTokens());
        map.put("outputTokens", usage.outputTokens());
        map.put("llmCalls", usage.llmCalls());
        map.put("cacheHits", usage.cacheHits());
        map.put("costUsd", usage.costUsd().toPlainString());
        return map;
    }

    private long hardTimeoutMillis(NodeDefinition node) {
        RetryPolicy retry = node.effectiveRetry(options.defaultMaxAttempts());
        long backoffTotal = 0L;
        for (int attempt = 1; attempt < Math.max(1, retry.maxAttempts()); attempt++) {
            backoffTotal += retry.backoffFor(attempt).toMillis();
        }
        return node.timeout().toMillis() * Math.max(1, retry.maxAttempts())
                + backoffTotal + options.hardTimeoutSlack().toMillis();
    }

    private static boolean sleepInterruptibly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String rootMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    /** Retryable unless an exception on the cause chain explicitly says otherwise. */
    private static boolean isRetryable(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof LlmException llmEx) {
                return llmEx.retryable();
            }
            if (cursor instanceof LoomVaultException vaultEx) {
                ErrorCode code = vaultEx.code();
                if (code == ErrorCode.NODE_NO_HANDLER
                        || code == ErrorCode.EXPRESSION_SYNTAX_ERROR
                        || code == ErrorCode.TEMPLATE_RENDER_ERROR
                        || code == ErrorCode.BUDGET_EXCEEDED) {
                    return false;
                }
            }
            if (cursor instanceof CompletionException && cursor.getCause() != null) {
                cursor = cursor.getCause();
                continue;
            }
            cursor = cursor.getCause();
        }
        return true;
    }

    // =========================================================================================
    // Internal state
    // =========================================================================================

    /**
     * All mutable state for one driven execution.
     *
     * <p><b>Threading:</b> everything except {@code cancelled} is touched only by the scheduler
     * thread. Workers read immutable snapshots passed through {@link NodeInvocation} and post
     * results to {@code completions}.</p>
     */
    private final class ExecutionRun {

        private final WorkflowGraph graph;
        private final ReplayIndex replay;
        private final BudgetLedger budget;

        // nodeStates is written by workers (attempt lifecycle) and read by the scheduler, hence a
        // concurrent map. Every other collection below is scheduler-thread-confined.
        private final Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();
        private final Map<String, Integer> settled = new LinkedHashMap<>();
        private final Map<String, Integer> satisfied = new LinkedHashMap<>();
        private final Deque<String> ready = new ArrayDeque<>();
        private final Set<String> enqueued = new LinkedHashSet<>();
        private final Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        private final Map<String, CompletableFuture<NodeOutcome>> inFlight = new LinkedHashMap<>();
        private final Map<String, Integer> replayAttempts = new LinkedHashMap<>();
        private final BlockingQueue<NodeCompletion> completions = new LinkedBlockingQueue<>();

        private volatile Execution execution;
        private volatile boolean cancelled;
        private boolean degraded;
        private boolean degradeAnnounced;
        private AbortReason abortReason;

        ExecutionRun(WorkflowGraph graph, Execution execution, ReplayIndex replay, BudgetLedger budget) {
            this.graph = graph;
            this.execution = execution;
            this.replay = replay;
            this.budget = budget;
            graph.nodeIds().forEach(id -> {
                settled.put(id, 0);
                satisfied.put(id, 0);
            });
            replay.attempts().forEach(replayAttempts::put);
        }

        Map<String, Map<String, Object>> snapshot() {
            return Map.copyOf(outputs);
        }

        Map<String, Integer> attemptView() {
            Map<String, Integer> view = new LinkedHashMap<>(replayAttempts);
            nodeStates.forEach((id, state) -> view.merge(id, state.attempt(), Math::max));
            return view;
        }
    }

    private record NodeOutcome(Status status,
                               Map<String, Object> output,
                               Usage usage,
                               Map<String, Object> approvalRequest,
                               ErrorCode errorCode,
                               String errorMessage,
                               int attempts) {

        enum Status {COMPLETED, AWAIT_APPROVAL, FAILED}

        static NodeOutcome completed(Map<String, Object> output, Usage usage, int attempts) {
            return new NodeOutcome(Status.COMPLETED, output == null ? Map.of() : output,
                    usage == null ? Usage.ZERO : usage, Map.of(), null, null, attempts);
        }

        static NodeOutcome awaiting(Map<String, Object> request, int attempts) {
            return new NodeOutcome(Status.AWAIT_APPROVAL, Map.of(), Usage.ZERO,
                    request == null ? Map.of() : request, null, null, attempts);
        }

        static NodeOutcome failed(ErrorCode code, String message, boolean retryable, int attempts) {
            return new NodeOutcome(Status.FAILED, Map.of(), Usage.ZERO, Map.of(), code, message, attempts);
        }

        static NodeOutcome failed(ErrorCode code, String message, boolean retryable) {
            return failed(code, message, retryable, 0);
        }
    }

    private record NodeCompletion(String nodeId, NodeOutcome outcome) {
    }

    private record AbortReason(ErrorCode code, String message) {
    }

    // =========================================================================================
    // Builder
    // =========================================================================================

    public static final class Builder {
        private WorkflowRegistry workflows = new WorkflowRegistry();
        private ExecutionRepository repository;
        private JournalStore journalStore;
        private EventPublisher eventPublisher = EventPublisher.NOOP;
        private NodeHandlerRegistry handlers;
        private LlmGateway llm;
        private CostModel costModel = CostModel.defaults();
        private Clock clock = Clock.systemUTC();
        private EngineOptions options = EngineOptions.defaults();

        public Builder workflows(WorkflowRegistry value) {
            this.workflows = value;
            return this;
        }

        public Builder repository(ExecutionRepository value) {
            this.repository = value;
            return this;
        }

        public Builder journalStore(JournalStore value) {
            this.journalStore = value;
            return this;
        }

        public Builder eventPublisher(EventPublisher value) {
            this.eventPublisher = value == null ? EventPublisher.NOOP : value;
            return this;
        }

        /** Registers the standard LLM / TOOL / APPROVAL handlers wired to the given tool registry. */
        public Builder standardHandlers(ToolRegistry toolRegistry) {
            this.handlers = NodeHandlerRegistry.of(
                    new io.loomvault.core.engine.handler.LlmNodeHandler(),
                    new io.loomvault.core.engine.handler.ToolNodeHandler(toolRegistry),
                    new io.loomvault.core.engine.handler.ApprovalNodeHandler());
            return this;
        }

        public Builder handlers(NodeHandlerRegistry value) {
            this.handlers = value;
            return this;
        }

        public Builder llm(LlmGateway value) {
            this.llm = value;
            return this;
        }

        public Builder costModel(CostModel value) {
            this.costModel = value == null ? CostModel.defaults() : value;
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = value == null ? Clock.systemUTC() : value;
            return this;
        }

        public Builder options(EngineOptions value) {
            this.options = value == null ? EngineOptions.defaults() : value;
            return this;
        }

        public ExecutionEngine build() {
            if (repository == null) {
                throw new IllegalStateException("ExecutionRepository is required");
            }
            if (journalStore == null) {
                throw new IllegalStateException("JournalStore is required");
            }
            if (handlers == null) {
                throw new IllegalStateException("Node handlers are required "
                        + "(call standardHandlers(...) or provide a NodeHandlerRegistry)");
            }
            if (llm == null) {
                throw new IllegalStateException("LlmGateway is required "
                        + "(use the deterministic mock gateway for offline runs)");
            }
            return new ExecutionEngine(this);
        }
    }
}
