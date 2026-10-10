package io.loomvault.core.engine.handler;

import io.loomvault.core.budget.CostModel;
import io.loomvault.core.engine.NodeContext;
import io.loomvault.core.engine.NodeHandler;
import io.loomvault.core.engine.NodeInvocation;
import io.loomvault.core.engine.NodeResult;
import io.loomvault.core.json.LoomJson;
import io.loomvault.core.llm.ChatMessage;
import io.loomvault.core.llm.ChatRequest;
import io.loomvault.core.llm.ChatResponse;
import io.loomvault.core.llm.TokenUsage;
import io.loomvault.core.model.NodeKind;
import io.loomvault.core.model.Usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes {@link NodeKind#LLM} nodes.
 *
 * <p>Responsibilities are deliberately narrow: render the prompt, call the gateway, translate the
 * response into a node output and a {@link Usage}. Everything expensive — caching, retries,
 * circuit breaking — happens behind the {@code LlmGateway} decorator chain, so this handler stays
 * a pure translation layer that is trivial to test.</p>
 *
 * <h3>Output shape</h3>
 * <pre>
 * {
 *   "text":   "the completion",
 *   "model":  "mock-fast",         // may differ from requested after DEGRADE
 *   "cached": false,
 *   "parsed": true,                // only for outputFormat=json
 *   "json":   { ... },             // parsed object when outputFormat=json
 *   ...                            // plus every top-level field of "json", flattened
 * }
 * </pre>
 * <p>The flattening is what lets an edge condition read
 * {@code nodes.classify.category == "billing"} instead of
 * {@code nodes.classify.json.category}.</p>
 */
public final class LlmNodeHandler implements NodeHandler {

    private static final String DEFAULT_MODEL = "mock-fast";

    @Override
    public NodeKind kind() {
        return NodeKind.LLM;
    }

    @Override
    public NodeResult execute(NodeInvocation invocation) {
        NodeContext ctx = invocation.context();

        String requestedModel = invocation.configString("model", DEFAULT_MODEL);
        String servedModel = requestedModel;
        if (ctx.degraded()) {
            // The workflow budget asked for a cheaper model; honour it only if the node declares
            // a fallback, otherwise stay with the requested model and let the budget guard decide.
            String fallback = invocation.configString("fallbackModel", null);
            if (fallback != null && !fallback.isBlank()) {
                servedModel = fallback;
            }
        }

        String system = invocation.renderedOr("system", null);
        String prompt = ctx.render(invocation.requiredConfig("prompt"));
        double temperature = invocation.configDouble("temperature", 0.2d);
        int maxTokens = invocation.configInt("maxTokens", 0);
        String outputFormat = invocation.configString("outputFormat", "text");

        List<ChatMessage> messages = new ArrayList<>(2);
        if (system != null && !system.isBlank()) {
            messages.add(ChatMessage.system(system));
        }
        messages.add(ChatMessage.user(prompt));

        ChatRequest request = new ChatRequest(servedModel, messages, temperature, maxTokens, outputFormat);
        ChatResponse response = ctx.llm().complete(request);

        CostModel costModel = ctx.costModel();
        TokenUsage tokens = response.usage();
        BigDecimal cost = response.fromCache()
                ? BigDecimal.ZERO
                : costModel.estimate(response.model(), tokens.inputTokens(), tokens.outputTokens());
        Usage usage = new Usage(
                tokens.inputTokens(),
                tokens.outputTokens(),
                response.fromCache() ? 0L : 1L,
                response.fromCache() ? 1L : 0L,
                cost);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", response.content());
        output.put("model", response.model());
        output.put("cached", response.fromCache());

        if (request.expectsJson()) {
            Map<String, Object> parsed = tryParseObject(response.content());
            output.put("parsed", parsed != null);
            if (parsed != null) {
                output.put("json", parsed);
                parsed.forEach(output::putIfAbsent);
            }
        }

        return NodeResult.of(output, usage);
    }

    /**
     * Best-effort extraction of a JSON object from a completion.
     *
     * <p>Models wrap JSON in prose far more often than their documentation admits, so the parser
     * also accepts a fenced block or the first balanced brace-delimited region. A failed parse is
     * not an error: the raw text stays available under {@code text} and {@code parsed=false}, and
     * downstream conditions simply see absent fields.</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> tryParseObject(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String candidate = content.trim();

        // Strip a ```json ... ``` fence if present.
        if (candidate.startsWith("```")) {
            int firstNewline = candidate.indexOf('\n');
            int lastFence = candidate.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                candidate = candidate.substring(firstNewline + 1, lastFence).trim();
            }
        }

        Map<String, Object> direct = parseIfObject(candidate);
        if (direct != null) {
            return direct;
        }

        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return parseIfObject(candidate.substring(start, end + 1));
        }
        return null;
    }

    private static Map<String, Object> parseIfObject(String text) {
        try {
            Map<String, Object> parsed = LoomJson.readMap(text);
            return parsed.isEmpty() ? null : parsed;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
