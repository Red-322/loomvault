package io.loomvault.core.engine;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * A deterministic, side-effecting capability callable from a workflow.
 *
 * <p>Tools are the escape hatch that keeps the engine small: fetching a row, calling an internal
 * API, writing a file, posting a comment. They are ordinary Java objects registered at startup,
 * not plugins loaded from disk — the point is that a reviewer can see every capability the
 * deployment has in one file.</p>
 *
 * <p>Contract for implementors:</p>
 * <ul>
 *   <li>return a JSON object (a {@code Map}); never {@code null};</li>
 *   <li>declare {@code idempotent=false} if a repeated call would double an external effect —
 *       the engine logs a recovery warning when such a tool is replayed after a crash;</li>
 *   <li>do not swallow exceptions: throwing lets the retry policy and the journal record what
 *       happened.</li>
 * </ul>
 */
public interface Tool {

    /** Name used in workflow definitions ({@code config.tool}). */
    String name();

    /** One-line description surfaced by {@code GET /api/tools}. */
    default String description() {
        return "";
    }

    /** Whether re-invoking the same call is safe. Registered tools rarely are. */
    default boolean idempotent() {
        return true;
    }

    /**
     * Runs the tool.
     *
     * @param args    rendered arguments (templates already interpolated)
     * @param context node context: inputs, upstream outputs and the model gateway
     * @return tool result as a JSON object
     */
    Map<String, Object> invoke(Map<String, Object> args, NodeContext context);

    /** Lambda-friendly factory so that trivial tools stay one-liners. */
    static Tool of(String name, String description, boolean idempotent,
                   BiFunction<Map<String, Object>, NodeContext, Map<String, Object>> body) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public boolean idempotent() {
                return idempotent;
            }

            @Override
            public Map<String, Object> invoke(Map<String, Object> args, NodeContext context) {
                return body.apply(args, context);
            }
        };
    }
}
