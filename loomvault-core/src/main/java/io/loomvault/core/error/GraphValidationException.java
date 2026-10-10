package io.loomvault.core.error;

import java.util.List;
import java.util.Map;

/**
 * Thrown when a workflow definition cannot be turned into a schedulable graph.
 *
 * <p>Carries the offending node ids or the detected cycle path so that operators can fix the
 * definition without bisecting it by hand.</p>
 */
public class GraphValidationException extends LoomVaultException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> offenders;

    public GraphValidationException(ErrorCode code, String message, List<String> offenders) {
        super(code, message, null, Map.of("offenders", offenders == null ? List.of() : List.copyOf(offenders)));
        this.offenders = offenders == null ? List.of() : List.copyOf(offenders);
    }

    public GraphValidationException(ErrorCode code, String message, List<String> offenders, Map<String, Object> context) {
        super(code, message, null, context);
        this.offenders = offenders == null ? List.of() : List.copyOf(offenders);
    }

    /** Node ids or the concrete cycle path that made the definition invalid. */
    public List<String> offenders() {
        return offenders;
    }
}
