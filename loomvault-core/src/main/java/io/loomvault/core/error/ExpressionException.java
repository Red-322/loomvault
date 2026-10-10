package io.loomvault.core.error;

import java.util.Map;

/**
 * Raised by the expression engine for syntax and evaluation problems.
 *
 * <p>Includes the source text plus the character offset when the failure is syntactic, which
 * makes the error message actionable instead of "invalid condition".</p>
 */
public class ExpressionException extends LoomVaultException {

    private static final long serialVersionUID = 1L;

    private final String source;
    private final int offset;

    public ExpressionException(ErrorCode code, String source, int offset, String message) {
        super(code, message, null, Map.of("source", source, "offset", offset));
        this.source = source;
        this.offset = offset;
    }

    public ExpressionException(ErrorCode code, String source, int offset, String message, Throwable cause) {
        super(code, message, cause, Map.of("source", source, "offset", offset));
        this.source = source;
        this.offset = offset;
    }

    /** The original expression text. */
    public String source() {
        return source;
    }

    /** Character offset in {@link #source()} where the problem was detected, or {@code -1}. */
    public int offset() {
        return offset;
    }
}
