package io.loomvault.core.error;

import java.util.Map;

/**
 * Raised when a node handler fails, times out, or is missing for a requested node kind.
 *
 * <p>The engine wraps every handler failure in this type so callers get a uniform view of node
 * failures regardless of what the handler threw underneath.</p>
 */
public class NodeExecutionException extends LoomVaultException {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final int attempt;

    public NodeExecutionException(ErrorCode code, String nodeId, int attempt, String message, Throwable cause) {
        super(code, message, cause, Map.of("nodeId", nodeId, "attempt", attempt));
        this.nodeId = nodeId;
        this.attempt = attempt;
    }

    public String nodeId() {
        return nodeId;
    }

    /** 1-based attempt counter at the moment of failure. */
    public int attempt() {
        return attempt;
    }
}
