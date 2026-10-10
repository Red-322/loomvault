package io.loomvault.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A directed edge between two nodes, optionally guarded by a condition expression.
 *
 * <p>Conditions are evaluated against the completed execution context, e.g.
 * {@code nodes.score.overall >= 7 && inputs.priority == "P1"}. An edge without a condition is
 * always taken once its source node completes — which is also how fan-out works: a node with
 * three unconditional outgoing edges starts three branches in parallel.</p>
 *
 * @param from      source node id
 * @param to        target node id
 * @param condition optional boolean expression; {@code null} means "always"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EdgeDefinition(String from, String to, String condition) {

    public EdgeDefinition {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("edge source must not be blank");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("edge target must not be blank");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("self-loop on node '" + from + "' is not allowed");
        }
        condition = (condition == null || condition.isBlank()) ? null : condition;
    }

    public static EdgeDefinition of(String from, String to) {
        return new EdgeDefinition(from, to, null);
    }

    public static EdgeDefinition when(String from, String to, String condition) {
        return new EdgeDefinition(from, to, condition);
    }

    public boolean hasCondition() {
        return condition != null;
    }
}
