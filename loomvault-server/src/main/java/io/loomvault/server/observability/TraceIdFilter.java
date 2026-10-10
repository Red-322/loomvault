package io.loomvault.server.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation id on every request.
 *
 * <p>The id lands in three places: the SLF4J {@link MDC} (so every log line of the request —
 * including the engine's own lines, which add {@code executionId} on top — can be grepped with one
 * token), the {@code X-Trace-Id} response header, and the {@code traceId} field of any error body.
 * A user reporting "it failed" can quote one string and the operator can find the whole story.</p>
 *
 * <p>Incoming {@code X-Trace-Id} headers are honoured so that a caller (or a load balancer that
 * already assigns ids) stays in control of the correlation chain.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = "loomvault.traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = sanitise(request.getHeader(HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put("traceId", traceId);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always clear: servlet threads are pooled, and a leaked trace id would attach
            // one request's identity to the next one's log lines.
            MDC.remove("traceId");
        }
    }

    /** Accepts only short, url-safe ids from untrusted callers. */
    private static String sanitise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.length() > 64) {
            trimmed = trimmed.substring(0, 64);
        }
        return trimmed.matches("[A-Za-z0-9._-]+") ? trimmed : null;
    }
}
