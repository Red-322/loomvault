package io.loomvault.core.engine.handler;

import io.loomvault.core.engine.NodeContext;
import io.loomvault.core.engine.NodeHandler;
import io.loomvault.core.engine.NodeInvocation;
import io.loomvault.core.engine.NodeResult;
import io.loomvault.core.engine.Tool;
import io.loomvault.core.engine.ToolRegistry;
import io.loomvault.core.model.NodeKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes {@link NodeKind#TOOL} nodes by delegating to the {@link ToolRegistry}.
 *
 * <p>Arguments are templated before invocation, recursively: a tool that takes
 * {@code {"ticketId": "${inputs.ticket.id}", "tags": ["${nodes.classify.category}"]}} is a common
 * shape in real workflows, and making the caller hand-render each string would be busywork.</p>
 *
 * <p>Tool results are flattened into the node output as well as nested under {@code result}, so
 * both {@code nodes.fetch_ticket.title} and {@code nodes.fetch_ticket.result.title} work. The
 * duplication costs nothing and removes a recurring source of confusing conditions.</p>
 */
public final class ToolNodeHandler implements NodeHandler {

    private final ToolRegistry tools;

    public ToolNodeHandler(ToolRegistry tools) {
        this.tools = tools;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.TOOL;
    }

    @Override
    public NodeResult execute(NodeInvocation invocation) {
        String toolName = invocation.requiredConfig("tool");
        Tool tool = tools.require(toolName);
        NodeContext ctx = invocation.context();

        Map<String, Object> args = renderValue(invocation.configMap("args"), ctx);
        Map<String, Object> result = tool.invoke(args, ctx);
        Map<String, Object> safeResult = result == null ? Map.of() : result;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tool", toolName);
        output.put("idempotent", tool.idempotent());
        output.put("result", safeResult);
        safeResult.forEach(output::putIfAbsent);
        return NodeResult.of(output);
    }

    /** Depth-first template rendering over maps and lists. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> renderValue(Map<String, Object> source, NodeContext ctx) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        source.forEach((key, value) -> rendered.put(key, renderNode(value, ctx)));
        return rendered;
    }

    @SuppressWarnings("unchecked")
    private static Object renderNode(Object value, NodeContext ctx) {
        if (value instanceof String text) {
            return ctx.render(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            map.forEach((k, v) -> rendered.put(String.valueOf(k), renderNode(v, ctx)));
            return rendered;
        }
        if (value instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object item : list) {
                rendered.add(renderNode(item, ctx));
            }
            return rendered;
        }
        return value;
    }
}
