package io.loomvault.core.model;

import java.util.Locale;

/**
 * The set of node kinds the engine knows how to execute.
 *
 * <p>Deliberately small. Anything that needs custom behaviour should be expressed as a
 * {@link #TOOL} and registered through the tool registry rather than growing this enum —
 * the engine schedules, the handlers do the work.</p>
 */
public enum NodeKind {

    /**
     * Calls the configured model gateway with a rendered prompt template.
     * The dominant consumer of the execution budget.
     */
    LLM,

    /**
     * Invokes a deterministic function registered in the {@code ToolRegistry}
     * (HTTP fetch, data transform, file write, side-effecting business action, ...).
     */
    TOOL,

    /**
     * Parks the execution until a human approves or rejects it.
     * This is what makes LoomVault a human-in-the-loop engine rather than a batch runner.
     */
    APPROVAL;

    /** Case-insensitive lookup that produces a helpful error message for typos. */
    public static NodeKind fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("node kind must not be blank");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "unknown node kind '" + raw + "'; supported values are LLM, TOOL, APPROVAL");
        }
    }
}
