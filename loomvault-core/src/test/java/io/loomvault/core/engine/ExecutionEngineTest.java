package io.loomvault.core.engine;

import io.loomvault.core.budget.CostModel;
import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.core.journal.JournalEntry;
import io.loomvault.core.journal.JournalEntryType;
import io.loomvault.core.journal.JournalStore;
import io.loomvault.core.llm.ChatRequest;
import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.LlmGateway;
import io.loomvault.core.llm.TokenUsage;
import io.loomvault.core.error.LlmException;
import io.loomvault.core.model.BudgetPolicy;
import io.loomvault.core.model.BudgetStrategy;
import io.loomvault.core.model.Execution;
import io.loomvault.core.model.ExecutionStatus;
import io.loomvault.core.model.NodeDefinition;
import io.loomvault.core.model.NodeKind;
import io.loomvault.core.model.NodeState;
import io.loomvault.core.model.NodeStatus;
import io.loomvault.core.model.RetryPolicy;
import io.loomvault.core.model.WorkflowDefinition;
import io.loomvault.core.port.ExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end behaviour of {@link ExecutionEngine} assembled from in-memory ports and a
 * deterministic gateway.
 *
 * <p><b>Why the ports are local test doubles.</b> {@code loomvault-core} deliberately depends on
 * nothing but Jackson and SLF4J, and its POM declares no test dependency on the {@code store} or
 * {@code llm} modules — so {@code InMemoryExecutionRepository}, {@code InMemoryJournalStore} and
 * {@code DeterministicMockProvider} are not visible from here. Rather than change the build, this
 * test carries equivalent doubles that reproduce the same observable contracts (revision
 * ownership, monotonic journal sequence, latency-free deterministic completions).</p>
 *
 * <p>Every test drives the engine to a terminal or parked state and asserts on the returned
 * {@link Execution} plus the persisted node states and journal.</p>
 */
class ExecutionEngineTest {

    private FakeExecutionRepository repository;
    private FakeJournalStore journal;
    private ToolRegistry tools;
    private WorkflowRegistry workflows;
    private ExecutionEngine engine;

    @BeforeEach
    void setUp() {
        repository = new FakeExecutionRepository();
        journal = new FakeJournalStore();
        workflows = new WorkflowRegistry();
        tools = new ToolRegistry()
                .register("echo", "Echoes the argument 'value' back", true, ExecutionEngineTest::echo)
                .register("flag", "Returns the boolean argument 'pass'", true, ExecutionEngineTest::flag)
                .register("boom", "Always fails", false, ExecutionEngineTest::boom);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // =========================================================================================
    // Fixtures and helpers
    // =========================================================================================

    private ExecutionEngine engine(StubLlmGateway gateway) {
        engine = ExecutionEngine.builder()
                .workflows(workflows)
                .repository(repository)
                .journalStore(journal)
                .standardHandlers(tools)
                .llm(gateway)
                .costModel(CostModel.defaults())
                .clock(Clock.systemUTC())
                .options(EngineOptions.builder()
                        .maxParallelNodes(4)
                        .defaultMaxAttempts(1)
                        .pollInterval(Duration.ofMillis(5))
                        .hardTimeoutSlack(Duration.ofSeconds(1))
                        .build())
                .build();
        return engine;
    }

    private static Map<String, Object> echo(Map<String, Object> args, NodeContext context) {
        return Map.of("value", String.valueOf(args.getOrDefault("value", "")));
    }

    private static Map<String, Object> flag(Map<String, Object> args, NodeContext context) {
        return Map.of("pass", Boolean.TRUE.equals(args.get("pass")));
    }

    private static Map<String, Object> boom(Map<String, Object> args, NodeContext context) {
        throw new IllegalStateException("intentional failure");
    }

    private static NodeDefinition echoNode(String id, String value) {
        return NodeDefinition.tool(id, "echo", Map.of("value", value));
    }

    private static NodeDefinition failingOptionalNode(String id) {
        // Canonical record constructor: (id, name, kind, config, retry, timeout, budget, optional, idempotent)
        return new NodeDefinition(id, id, NodeKind.TOOL, Map.of("tool", "boom"),
                RetryPolicy.NO_RETRY, NodeDefinition.DEFAULT_TIMEOUT, null, true, true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nodeOutputs(Execution execution) {
        return (Map<String, Object>) execution.outputs().get("nodes");
    }

    @SuppressWarnings("unchecked")
    private static Object outputValue(Execution execution, String nodeId, String key) {
        Object node = nodeOutputs(execution).get(nodeId);
        return node == null ? null : ((Map<String, Object>) node).get(key);
    }

    private NodeState stateOf(String executionId, String nodeId) {
        return repository.loadNodeStates(executionId).get(nodeId);
    }

    private long countJournalEntries(String executionId, JournalEntryType type, String nodeId) {
        return journal.readAll(executionId).stream()
                .filter(entry -> entry.type() == type)
                .filter(entry -> nodeId == null || nodeId.equals(entry.nodeId()))
                .count();
    }

    // =========================================================================================
    // Topology: linear, parallel, conditional
    // =========================================================================================

    @Test
    @DisplayName("runs a linear graph to completion and exposes every node output")
    void shouldExecuteLinearGraph() {
        workflows.register(WorkflowDefinition.builder()
                .id("linear")
                .node(echoNode("a", "A"))
                .node(echoNode("b", "B"))
                .node(echoNode("c", "C"))
                .edge("a", "b").edge("b", "c")
                .build());
        engine(new StubLlmGateway(0));

        Execution execution = engine.start("linear", ExecutionRequest.empty());

        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(nodeOutputs(execution)).containsKeys("a", "b", "c");
        assertThat(outputValue(execution, "c", "value")).isEqualTo("C");
        assertThat(countJournalEntries(execution.id(), JournalEntryType.EXECUTION_FINISHED, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("fans out to two branches and fans them back into a join that sees both outputs")
    void shouldFanOutAndFanIn() {
        workflows.register(WorkflowDefinition.builder()
                .id("fan")
                .node(echoNode("a", "A"))
                .node(echoNode("b", "${nodes.a.value}B"))
                .node(echoNode("c", "${nodes.a.value}C"))
                .node(echoNode("d", "${nodes.b.value}+${nodes.c.value}"))
                .edge("a", "b").edge("a", "c")
                .edge("b", "d").edge("c", "d")
                .build());
        engine(new StubLlmGateway(0));

        Execution execution = engine.start("fan", ExecutionRequest.empty());

        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(outputValue(execution, "b", "value")).isEqualTo("AB");
        assertThat(outputValue(execution, "c", "value")).isEqualTo("AC");
        // The join observed both upstream branches.
        assertThat(outputValue(execution, "d", "value")).isEqualTo("AB+AC");
    }

    @Nested
    @DisplayName("conditional routing")
    class ConditionalRouting {

        private WorkflowDefinition gateWorkflow(String id, boolean pass) {
            return WorkflowDefinition.builder()
                    .id(id)
                    .node(NodeDefinition.tool("gate", "flag", Map.of("pass", pass)))
                    .node(echoNode("taken", "taken"))
                    .node(echoNode("skipped", "skipped"))
                    .edge("gate", "taken", "nodes.gate.pass == true")
                    .edge("gate", "skipped", "nodes.gate.pass == false")
                    .build();
        }

        @Test
        @DisplayName("executes the true branch and skips the false branch")
        void shouldExecuteTrueBranchOnly() {
            workflows.register(gateWorkflow("route-true", true));
            engine(new StubLlmGateway(0));

            Execution execution = engine.start("route-true", ExecutionRequest.empty());

            assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(stateOf(execution.id(), "taken").status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(stateOf(execution.id(), "skipped").status()).isEqualTo(NodeStatus.SKIPPED);
        }

        @Test
        @DisplayName("executes the false branch and skips the true branch")
        void shouldExecuteFalseBranchOnly() {
            workflows.register(gateWorkflow("route-false", false));
            engine(new StubLlmGateway(0));

            Execution execution = engine.start("route-false", ExecutionRequest.empty());

            assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(stateOf(execution.id(), "taken").status()).isEqualTo(NodeStatus.SKIPPED);
            assertThat(stateOf(execution.id(), "skipped").status()).isEqualTo(NodeStatus.COMPLETED);
        }
    }

    // =========================================================================================
    // Retry and failure handling
    // =========================================================================================

    @Test
    @DisplayName("retries a flaky node and records NODE_FAILED and NODE_RETRY_SCHEDULED in the journal")
    void shouldRetryFlakyNodeUntilSuccess() {
        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .id("retry")
                .node(NodeDefinition.llm("flaky", "mock-fast", "Say something useful")
                        .withRetry(RetryPolicy.fixed(3, Duration.ofMillis(1))))
                .build();
        workflows.register(workflow);
        StubLlmGateway gateway = new StubLlmGateway(2); // first two calls fail, third succeeds
        engine(gateway);

        Execution execution = engine.start("retry", ExecutionRequest.empty());

        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(gateway.callCount()).isEqualTo(3);
        assertThat(stateOf(execution.id(), "flaky").status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(countJournalEntries(execution.id(), JournalEntryType.NODE_FAILED, "flaky")).isEqualTo(2);
        assertThat(countJournalEntries(execution.id(), JournalEntryType.NODE_RETRY_SCHEDULED, "flaky")).isEqualTo(2);
    }

    @Test
    @DisplayName("an optional node failure is recorded but does not abort the execution; the join still runs")
    void shouldNotAbortWhenOptionalNodeFails() {
        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .id("optional")
                .node(echoNode("start", "S"))
                .node(failingOptionalNode("risky"))
                .node(echoNode("safe", "SAFE"))
                .node(echoNode("merge", "${nodes.safe.value}-MERGED"))
                .edge("start", "risky")
                .edge("start", "safe")
                .edge("risky", "merge")
                .edge("safe", "merge")
                .build();
        workflows.register(workflow);
        engine(new StubLlmGateway(0));

        Execution execution = engine.start("optional", ExecutionRequest.empty());

        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(stateOf(execution.id(), "risky").status()).isEqualTo(NodeStatus.FAILED);
        // The join is a successor of the failed node and still executed.
        assertThat(stateOf(execution.id(), "merge").status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(outputValue(execution, "merge", "value")).isEqualTo("SAFE-MERGED");
    }

    @Test
    @DisplayName("a non-optional node failure terminates the execution as FAILED and skips the rest")
    void shouldTerminateWhenRequiredNodeFails() {
        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .id("hard-fail")
                .node(NodeDefinition.tool("boom", "boom", Map.of()))
                .node(echoNode("never", "never"))
                .edge("boom", "never")
                .build();
        workflows.register(workflow);
        engine(new StubLlmGateway(0));

        Execution execution = engine.start("hard-fail", ExecutionRequest.empty());

        assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.failureCode()).isEqualTo(ErrorCode.NODE_FAILED.name());
        assertThat(stateOf(execution.id(), "boom").status()).isEqualTo(NodeStatus.FAILED);
        assertThat(stateOf(execution.id(), "never").status()).isEqualTo(NodeStatus.SKIPPED);
    }

    // =========================================================================================
    // Human-in-the-loop
    // =========================================================================================

    @Test
    @DisplayName("parks on an approval node and resumes to success after approval")
    void shouldParkOnApprovalThenResume() {
        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .id("approval")
                .node(echoNode("prepare", "P"))
                .node(NodeDefinition.approval("gate", "Please approve the draft"))
                .node(echoNode("after", "DONE"))
                .edge("prepare", "gate").edge("gate", "after")
                .build();
        workflows.register(workflow);
        engine(new StubLlmGateway(0));

        Execution parked = engine.start("approval", ExecutionRequest.empty());
        assertThat(parked.status()).isEqualTo(ExecutionStatus.WAITING_APPROVAL);
        assertThat(stateOf(parked.id(), "gate").status()).isEqualTo(NodeStatus.WAITING_APPROVAL);

        Execution resumed = engine.approve(parked.id(), null, "tester", "looks good");

        assertThat(resumed.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(outputValue(resumed, "after", "value")).isEqualTo("DONE");
        assertThat(countJournalEntries(resumed.id(), JournalEntryType.APPROVAL_GRANTED, "gate")).isEqualTo(1);
    }

    // =========================================================================================
    // Idempotency
    // =========================================================================================

    @Test
    @DisplayName("starting twice with the same executionId returns the existing run without re-executing")
    void shouldBeIdempotentForRepeatedStart() {
        workflows.register(WorkflowDefinition.builder()
                .id("idempotent")
                .node(NodeDefinition.llm("only", "mock-fast", "Say hello"))
                .build());
        StubLlmGateway gateway = new StubLlmGateway(0);
        engine(gateway);
        ExecutionRequest request = ExecutionRequest.builder().executionId("fixed-execution-1").build();

        Execution first = engine.start("idempotent", request);
        Execution second = engine.start("idempotent", request);

        assertThat(first.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(gateway.callCount()).isEqualTo(1);
    }

    // =========================================================================================
    // Budget governance
    // =========================================================================================

    @Test
    @DisplayName("stops with BUDGET_EXCEEDED when the call budget is crossed under the FAIL strategy")
    void shouldStopWhenBudgetIsExceededWithFailStrategy() {
        BudgetPolicy policy = new BudgetPolicy(0L, 1L, null, 0L, BudgetStrategy.FAIL, 0.8d);
        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .id("budget")
                .budget(policy)
                .node(NodeDefinition.llm("one", "mock-fast", "First call"))
                .node(NodeDefinition.llm("two", "mock-fast", "Second call"))
                .edge("one", "two")
                .build();
        workflows.register(workflow);
        engine(new StubLlmGateway(0));

        Execution execution = engine.start("budget", ExecutionRequest.empty());

        assertThat(execution.status()).isEqualTo(ExecutionStatus.BUDGET_EXCEEDED);
        assertThat(execution.failureCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED.name());
        assertThat(countJournalEntries(execution.id(), JournalEntryType.BUDGET_EXCEEDED, null)).isEqualTo(1);
    }

    // =========================================================================================
    // Replay: the core selling point
    // =========================================================================================

    @Test
    @DisplayName("resuming after approval does not re-execute nodes that already completed")
    void shouldNotReExecuteCompletedNodesOnResume() {
        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .id("replay")
                .node(NodeDefinition.llm("first", "mock-fast", "First step"))
                .node(NodeDefinition.approval("gate", "Approve before the second step"))
                .node(NodeDefinition.llm("second", "mock-fast", "Second step"))
                .edge("first", "gate").edge("gate", "second")
                .build();
        workflows.register(workflow);
        StubLlmGateway gateway = new StubLlmGateway(0);
        engine(gateway);

        Execution parked = engine.start("replay", ExecutionRequest.empty());
        assertThat(parked.status()).isEqualTo(ExecutionStatus.WAITING_APPROVAL);
        int callsBeforeApproval = gateway.callCount();
        assertThat(callsBeforeApproval).isEqualTo(1); // only 'first' has run

        Execution resumed = engine.approve(parked.id(), null, "tester", "ok");

        assertThat(resumed.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        // Exactly one more call: 'second'. 'first' was restored from the journal, not re-run.
        assertThat(gateway.callCount()).isEqualTo(callsBeforeApproval + 1);
        assertThat(countJournalEntries(resumed.id(), JournalEntryType.NODE_STARTED, "first")).isEqualTo(1);
        assertThat(countJournalEntries(resumed.id(), JournalEntryType.NODE_COMPLETED, "first")).isEqualTo(1);
        assertThat(resumed.resumeCount()).isEqualTo(1);
    }

    // =========================================================================================
    // Test doubles
    // =========================================================================================

    /** Deterministic, latency-free gateway with a fixed number of injected transient failures. */
    static final class StubLlmGateway implements LlmGateway {

        private final AtomicInteger calls = new AtomicInteger();
        private final int failFirstCalls;

        StubLlmGateway(int failFirstCalls) {
            this.failFirstCalls = failFirstCalls;
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            int call = calls.incrementAndGet();
            if (call <= failFirstCalls) {
                throw new LlmException("injected transient failure on call " + call, true, 503, null);
            }
            String prompt = request.messages().get(request.messages().size() - 1).content();
            String content = "stub-completion:" + prompt;
            TokenUsage usage = new TokenUsage(Math.max(1, prompt.length()), Math.max(1, content.length()));
            return new ChatResponse(content, request.model(), usage, false, "stop", Map.of());
        }

        int callCount() {
            return calls.get();
        }
    }

    /**
     * In-memory {@link ExecutionRepository} with the same revision semantics as the production one:
     * {@code save} bumps the revision and rejects a stale value with {@code CONCURRENCY_CONFLICT}.
     */
    static final class FakeExecutionRepository implements ExecutionRepository {

        private final ConcurrentHashMap<String, Execution> executions = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Map<String, NodeState>> nodeStates = new ConcurrentHashMap<>();

        @Override
        public Execution create(Execution execution) {
            Execution existing = executions.putIfAbsent(execution.id(), execution);
            if (existing != null) {
                throw new LoomVaultException(ErrorCode.CONCURRENCY_CONFLICT,
                        "execution already exists: " + execution.id());
            }
            return execution;
        }

        @Override
        public Execution save(Execution execution) {
            return executions.compute(execution.id(), (id, current) -> {
                if (current == null || current.revision() != execution.revision()) {
                    throw new LoomVaultException(ErrorCode.CONCURRENCY_CONFLICT,
                            "stale revision for execution " + id);
                }
                return execution.withRevision(execution.revision() + 1);
            });
        }

        @Override
        public Optional<Execution> find(String executionId) {
            return Optional.ofNullable(executions.get(executionId));
        }

        @Override
        public List<Execution> findRecent(int limit) {
            return executions.values().stream()
                    .sorted(Comparator.comparing(Execution::createdAt).reversed())
                    .limit(Math.max(0, limit))
                    .toList();
        }

        @Override
        public List<Execution> findByStatus(Collection<ExecutionStatus> statuses, int limit) {
            if (statuses == null || statuses.isEmpty()) {
                return List.of();
            }
            return executions.values().stream()
                    .filter(execution -> statuses.contains(execution.status()))
                    .sorted(Comparator.comparing(Execution::createdAt).reversed())
                    .limit(Math.max(0, limit))
                    .toList();
        }

        @Override
        public long count() {
            return executions.size();
        }

        @Override
        public long countByStatus(ExecutionStatus status) {
            return executions.values().stream().filter(e -> e.status() == status).count();
        }

        @Override
        public void saveNodeState(String executionId, NodeState state) {
            nodeStates.computeIfAbsent(executionId, key -> new ConcurrentHashMap<>())
                    .put(state.nodeId(), state);
        }

        @Override
        public Map<String, NodeState> loadNodeStates(String executionId) {
            Map<String, NodeState> states = nodeStates.get(executionId);
            return states == null ? Map.of() : new LinkedHashMap<>(states);
        }
    }

    /** In-memory, append-only journal with a per-execution monotonic sequence starting at 1. */
    static final class FakeJournalStore implements JournalStore {

        private final ConcurrentHashMap<String, List<JournalEntry>> journals = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

        @Override
        public JournalEntry append(String executionId, JournalEntryType type, String nodeId,
                                   Map<String, Object> payload, Instant timestamp) {
            synchronized (locks.computeIfAbsent(executionId, key -> new Object())) {
                List<JournalEntry> entries = journals.computeIfAbsent(executionId,
                        key -> new CopyOnWriteArrayList<>());
                long seq = entries.isEmpty() ? 1L : entries.get(entries.size() - 1).seq() + 1;
                JournalEntry entry = JournalEntry.of(executionId, seq, type, nodeId, payload, timestamp);
                entries.add(entry);
                return entry;
            }
        }

        @Override
        public List<JournalEntry> readAll(String executionId) {
            List<JournalEntry> entries = journals.get(executionId);
            return entries == null ? List.of() : new ArrayList<>(entries);
        }

        @Override
        public long lastSeq(String executionId) {
            List<JournalEntry> entries = journals.get(executionId);
            return entries == null || entries.isEmpty() ? 0L : entries.get(entries.size() - 1).seq();
        }
    }
}
