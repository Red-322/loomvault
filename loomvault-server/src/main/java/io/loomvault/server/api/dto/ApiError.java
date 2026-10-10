package io.loomvault.server.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform error body for every non-2xx response.
 *
 * <p>The {@code code} field carries the stable {@code ErrorCode} so that clients (and the demo
 * scripts) branch on a contract rather than on message substrings, while {@code message} stays
 * free to become more helpful over time.</p>
 *
 * @param timestamp when the server produced the error
 * @param status    HTTP status, duplicated here so logs and payloads agree
 * @param code      stable machine-readable code
 * @param message   human-readable explanation
 * @param path      request path
 * @param traceId   correlation id, also returned in the {@code X-Trace-Id} header
 * @param context   extra machine-readable detail (cycle path, offending node, ...)
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        Map<String, Object> context
) {

    public ApiError {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static ApiError of(int status, String code, String message, String path, String traceId) {
        return new ApiError(Instant.now(), status, code, message, path, traceId, Map.of());
    }

    public static ApiError of(int status, String code, String message, String path, String traceId,
                              Map<String, Object> context) {
        return new ApiError(Instant.now(), status, code, message, path, traceId, context);
    }
}
