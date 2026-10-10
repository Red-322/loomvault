package io.loomvault.core.engine;

import io.loomvault.core.budget.BudgetLedger;
import io.loomvault.core.budget.CostModel;
import io.loomvault.core.expr.Expressions;
import io.loomvault.core.expr.StringTemplate;
import io.loomvault.core.llm.LlmGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything a node handler is allowed to see.
 *
 * <p>Two design decisions are worth calling out:</p>
 *
 * <ol>
 *   <li><b>The upstream view is a snapshot, taken when the node became ready.</b> A node's inputs
 *       are its predecessors' outputs — not "whatever else happened to have finished by the time
 *       this thread got scheduled". Without this, two identical executions could feed a node
 *       different data depending on thread timing, and replay would be impossible.</li>
 *   <li><b>Templates and conditions go through the same expression engine</b> used by the
 *       scheduler, so {@code ${nodes.score.overall}} in a prompt and {@code nodes.score.overall > 7}
 *       in an edge condition are guaranteed to agree on what that value is.</li>
 * </ol>
 */
public final class NodeContext {

    private final String executionId;
    private final String nodeId;
    private final Map<String, Object> inputs;
    private final Map<String, Map<String, Object>> nodeOutputs;
    private final Map<String, Object> variables;
    private final LlmGateway llm;
    private final CostModel costModel;
    private final Clock clock;
    private final BudgetLedger budget;
    private final Map<String, Integer> attempts;
    private final boolean degraded;

    private NodeContext(Builder builder) {
        this.executionId = builder.executionId;
        this.nodeId = builder.nodeId;
        this.inputs = Map.copyOf(builder.inputs);
        this.nodeOutputs = Map.copyOf(builder.nodeOutputs);
        this.llm = builder.llm;
        this.costModel = builder.costModel;
        this.clock = builder.clock;
        this.budget = builder.budget;
        this.attempts = Map.copyOf(builder.attempts);
        this.degraded = builder.degraded;

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("inputs", this.inputs);
        vars.put("nodes", this.nodeOutputs);
        vars.put("executionId", executionId);
        vars.put("nodeId", nodeId);
        this.variables = Map.copyOf(vars);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Identity -----------------------------------------------------------------------------

    public String executionId() {
        return executionId;
    }

    public String nodeId() {
        return nodeId;
    }

    // ---- Data access --------------------------------------------------------------------------

    /** Root variables used for expression evaluation and template rendering. */
    public Map<String, Object> variables() {
        return variables;
    }

    /** Workflow-level inputs. */
    public Map<String, Object> inputs() {
        return inputs;
    }

    /** Output of every node that had completed when this node became ready. */
    public Map<String, Map<String, Object>> nodeOutputs() {
        return nodeOutputs;
    }

    /** Output of a single completed node, or an empty map. */
    public Map<String, Object> outputOf(String upstreamNodeId) {
        return nodeOutputs.getOrDefault(upstreamNodeId, Map.of());
    }

    /** Attempts recorded so far for a node, useful for retry-aware prompts. */
    public int attemptOf(String otherNodeId) {
        return attempts.getOrDefault(otherNodeId, 0);
    }

    // ---- Evaluation ---------------------------------------------------------------------------

    /** Renders a {@code ${...}} template against this context. */
    public String render(String template) {
        return StringTemplate.render(template, variables);
    }

    /** Evaluates a condition expression against this context. */
    public boolean test(String condition) {
        return Expressions.evaluateBoolean(condition, variables);
    }

    /** Evaluates an arbitrary expression against this context. */
    public Object evaluate(String expression) {
        return Expressions.evaluate(expression, variables);
    }

    // ---- Collaborators ------------------------------------------------------------------------

    public LlmGateway llm() {
        return llm;
    }

    public CostModel costModel() {
        return costModel;
    }

    public Clock clock() {
        return clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public BudgetLedger budget() {
        return budget;
    }

    /**
     * True when the workflow budget has forced a downgrade. Handlers that know a cheaper model
     * (LLM nodes with a {@code fallbackModel} config key) should honour it; handlers that do not
     * simply ignore it.
     */
    public boolean degraded() {
        return degraded;
    }

    // ---- Builder ------------------------------------------------------------------------------

    public static final class Builder {
        private String executionId;
        private String nodeId;
        private Map<String, Object> inputs = Map.of();
        private Map<String, Map<String, Object>> nodeOutputs = Map.of();
        private LlmGateway llm;
        private CostModel costModel = CostModel.defaults();
        private Clock clock = Clock.systemUTC();
        private BudgetLedger budget;
        private Map<String, Integer> attempts = Map.of();
        private boolean degraded;

        public Builder executionId(String value) {
            this.executionId = value;
            return this;
        }

        public Builder nodeId(String value) {
            this.nodeId = value;
            return this;
        }

        public Builder inputs(Map<String, Object> value) {
            this.inputs = value == null ? Map.of() : value;
            return this;
        }

        public Builder nodeOutputs(Map<String, Map<String, Object>> value) {
            this.nodeOutputs = value == null ? Map.of() : value;
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

        public Builder budget(BudgetLedger value) {
            this.budget = value;
            return this;
        }

        public Builder attempts(Map<String, Integer> value) {
            this.attempts = value == null ? Map.of() : value;
            return this;
        }

        public Builder degraded(boolean value) {
            this.degraded = value;
            return this;
        }

        public NodeContext build() {
            if (llm == null) {
                throw new IllegalStateException("NodeContext requires an LlmGateway (use a no-op gateway if unused)");
            }
            return new NodeContext(this);
        }
    }
}
