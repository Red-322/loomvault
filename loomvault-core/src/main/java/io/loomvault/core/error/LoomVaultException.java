package io.loomvault.core.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base type for every exception raised by LoomVault.
 *
 * <p>Carries a stable {@link ErrorCode} plus an optional context map. The context map is echoed
 * to operators (API responses, logs) and never contains secrets: callers must redact prompts and
 * credentials before adding them.</p>
 */
public class LoomVaultException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Map<String, Object> context;

    public LoomVaultException(ErrorCode code, String message) {
        this(code, message, null, Map.of());
    }

    public LoomVaultException(ErrorCode code, String message, Throwable cause) {
        this(code, message, cause, Map.of());
    }

    public LoomVaultException(ErrorCode code, String message, Throwable cause, Map<String, Object> context) {
        super(message, cause);
        this.code = code;
        this.context = context == null || context.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /** Returns the stable error code. Never {@code null}. */
    public ErrorCode code() {
        return code;
    }

    /** Returns an immutable, non-secret context map describing where the failure happened. */
    public Map<String, Object> context() {
        return context;
    }

    /** Convenience factory that keeps call sites terse. */
    public static LoomVaultException of(ErrorCode code, String format, Object... args) {
        return new LoomVaultException(code, String.format(format, args));
    }
}
