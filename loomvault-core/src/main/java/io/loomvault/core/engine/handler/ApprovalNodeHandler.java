package io.loomvault.core.engine.handler;

import io.loomvault.core.engine.NodeHandler;
import io.loomvault.core.engine.NodeInvocation;
import io.loomvault.core.engine.NodeResult;
import io.loomvault.core.model.NodeKind;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes {@link NodeKind#APPROVAL} nodes — the human-in-the-loop seam.
 *
 * <p>The handler does no waiting of its own. It returns
 * {@link NodeResult#awaitingApproval(Map)}, and the engine parks the entire execution: state is
 * persisted, the process may restart, and a human may decide hours later. That is only possible
 * because waiting is expressed as data (a parked state) rather than as a blocked thread.</p>
 *
 * <h3>Config keys</h3>
 * <ul>
 *   <li>{@code summary} — what the approver sees (templated, e.g. references the draft under
 *       review)</li>
 *   <li>{@code details} — optional longer context</li>
 *   <li>{@code assignee} — optional owner hint surfaced in the UI</li>
 * </ul>
 */
public final class ApprovalNodeHandler implements NodeHandler {

    @Override
    public NodeKind kind() {
        return NodeKind.APPROVAL;
    }

    @Override
    public NodeResult execute(NodeInvocation invocation) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("summary", invocation.renderedOr("summary",
                "Approval required for node '" + invocation.nodeId() + "'"));
        request.put("nodeId", invocation.nodeId());

        String details = invocation.renderedOr("details", null);
        if (details != null && !details.isBlank()) {
            request.put("details", details);
        }
        String assignee = invocation.configString("assignee", null);
        if (assignee != null && !assignee.isBlank()) {
            request.put("assignee", assignee);
        }
        return NodeResult.awaitingApproval(request);
    }
}
