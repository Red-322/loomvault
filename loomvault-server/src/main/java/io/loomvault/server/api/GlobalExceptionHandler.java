package io.loomvault.server.api;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.GraphValidationException;
import io.loomvault.core.error.LoomVaultException;
import io.loomvault.server.api.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns every exception into a consistent {@link ApiError}.
 *
 * <p>Two rules keep this useful:</p>
 * <ol>
 *   <li><b>Error codes, not status guessing.</b> The mapping from {@link ErrorCode} to HTTP status
 *       lives here, in one switch. A rejected approval is a 409, not a 500 — the engine did
 *       exactly what it was told.</li>
 *   <li><b>No stack traces in payloads.</b> Internal messages are logged with the trace id;
 *       clients get the trace id. A model prompt echoed back in an error body is a data leak with
 *       a friendly name.</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GraphValidationException.class)
    public ResponseEntity<ApiError> handleGraphValidation(GraphValidationException ex,
                                                          HttpServletRequest request) {
        Map<String, Object> context = new LinkedHashMap<>(ex.context());
        if (!ex.offenders().isEmpty()) {
            context.put("offenders", ex.offenders());
        }
        log.debug("api.invalid_workflow code={} offenders={}", ex.code(), ex.offenders());
        return build(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage(), request, context);
    }

    @ExceptionHandler(LoomVaultException.class)
    public ResponseEntity<ApiError> handleDomain(LoomVaultException ex, HttpServletRequest request) {
        HttpStatus status = statusFor(ex.code());
        if (status.is5xxServerError()) {
            log.error("api.domain_error code={} message={}", ex.code(), ex.getMessage(), ex);
        } else {
            log.debug("api.domain_error code={} message={}", ex.code(), ex.getMessage());
        }
        return build(status, ex.code(), ex.getMessage(), request, ex.context());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_WORKFLOW,
                "request validation failed — " + detail, request, Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex,
                                                     HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_WORKFLOW,
                "request validation failed — " + ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_WORKFLOW,
                "request body could not be parsed as JSON", request, Map.of());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.EXECUTION_NOT_FOUND,
                "no handler for " + request.getRequestURI(), request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("api.unhandled path={}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "unexpected server error; quote the trace id when reporting this", request, Map.of());
    }

    // -----------------------------------------------------------------------------------------

    private ResponseEntity<ApiError> build(HttpStatus status, ErrorCode code, String message,
                                           HttpServletRequest request, Map<String, Object> context) {
        ApiError body = ApiError.of(status.value(), code.name(), message,
                request.getRequestURI(), currentTraceId(), context);
        return ResponseEntity.status(status)
                .header("X-Trace-Id", currentTraceId())
                // Machine consumers read the code; the header lets even a 500 be matched to a log.
                .header("X-Error-Code", code.name())
                .body(body);
    }

    /** Single source of truth for the code → status mapping. */
    static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case EXECUTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_WORKFLOW, GRAPH_CYCLE_DETECTED, GRAPH_DUPLICATE_NODE,
                 GRAPH_UNKNOWN_EDGE_ENDPOINT, GRAPH_NO_ENTRY_POINT,
                 EXPRESSION_SYNTAX_ERROR, EXPRESSION_EVALUATION_ERROR,
                 TEMPLATE_RENDER_ERROR -> HttpStatus.BAD_REQUEST;
            case EXECUTION_NOT_RESUMABLE, CONCURRENCY_CONFLICT, APPROVAL_REQUIRED,
                 APPROVAL_REJECTED, EXECUTION_CANCELLED, BUDGET_EXCEEDED -> HttpStatus.CONFLICT;
            case LLM_CIRCUIT_OPEN -> HttpStatus.SERVICE_UNAVAILABLE;
            case NODE_FAILED, NODE_TIMEOUT, NODE_NO_HANDLER, LLM_CALL_FAILED,
                 JOURNAL_CORRUPTED, REPLAY_DIVERGENCE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PERSISTENCE_ERROR, INTERNAL_ERROR, BUDGET_WARNING -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? "-" : traceId;
    }
}
