package io.loomvault.core.error;

import java.util.Map;

/**
 * Raised when a model provider call fails.
 *
 * <p>The {@code retryable} flag is the important part: a 500 or a timeout is worth retrying, an
 * invalid request or an authentication failure never is. Retrying non-retryable failures is how
 * agents turn a small mistake into a large invoice.</p>
 */
public class LlmException extends LoomVaultException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final int httpStatus;

    public LlmException(String message, boolean retryable, int httpStatus, Throwable cause) {
        super(ErrorCode.LLM_CALL_FAILED, message, cause,
                Map.of("retryable", retryable, "httpStatus", httpStatus));
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    /** HTTP status when the failure came from an HTTP provider; {@code -1} otherwise. */
    public int httpStatus() {
        return httpStatus;
    }
}
