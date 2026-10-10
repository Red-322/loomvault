package io.loomvault.server.api.dto;

/**
 * Body of {@code POST /api/executions/{id}/approve} and {@code .../reject}.
 *
 * @param nodeId   approval node to decide on; when {@code null} the first pending one is used
 * @param approver who is making the decision; recorded verbatim in the journal
 * @param comment  optional rationale, also journalled
 */
public record ApprovalRequest(String nodeId, String approver, String comment) {

    public static ApprovalRequest of(String approver) {
        return new ApprovalRequest(null, approver, null);
    }
}
