package io.loomvault.core.error;

import java.util.Map;

/**
 * Raised when an execution crosses a budget line and the configured strategy is {@code FAIL}.
 *
 * <p>Carries both the measured usage and the policy that was violated so the caller can prove
 * exactly why the run was stopped.</p>
 */
public class BudgetExceededException extends LoomVaultException {

    private static final long serialVersionUID = 1L;

    private final long usedTokens;
    private final long maxTokens;
    private final String scope;

    public BudgetExceededException(String message, String scope, long usedTokens, long maxTokens) {
        super(ErrorCode.BUDGET_EXCEEDED, message, null,
                Map.of("scope", scope, "usedTokens", usedTokens, "maxTokens", maxTokens));
        this.scope = scope;
        this.usedTokens = usedTokens;
        this.maxTokens = maxTokens;
    }

    /** Either {@code workflow} or the node id whose node-level budget was breached. */
    public String scope() {
        return scope;
    }

    public long usedTokens() {
        return usedTokens;
    }

    public long maxTokens() {
        return maxTokens;
    }
}
