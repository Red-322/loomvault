package io.loomvault.core.expr;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.ExpressionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal {@code ${...}} interpolation used to build prompts and tool arguments.
 *
 * <pre>
 *   "Summarise the ticket: ${inputs.ticket.title} (priority ${inputs.ticket.priority})"
 *   "Score given by the reviewer: ${nodes.review.score}"
 * </pre>
 *
 * <p>Escaping: a literal dollar-brace is written {@code \$\{...\}}. Rendering an unknown path
 * produces an empty string — the same totality rule the evaluator follows — while a malformed
 * expression is a configuration error and fails fast with
 * {@link ErrorCode#TEMPLATE_RENDER_ERROR} wrapped into an {@link ExpressionException}.</p>
 */
public final class StringTemplate {

    private StringTemplate() {
    }

    /** Renders {@code template} against {@code context}. */
    public static String render(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        int length = template.length();
        while (i < length) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                out.append(template, i, length);
                break;
            }
            boolean escaped = start > 0 && template.charAt(start - 1) == '\\';
            out.append(template, i, start);

            int end = closingBrace(template, start + 2);
            if (end < 0) {
                throw new ExpressionException(ErrorCode.TEMPLATE_RENDER_ERROR, template, start,
                        "unterminated '${' placeholder");
            }
            if (escaped) {
                // Drop the backslash that was already appended and emit the literal.
                out.setLength(out.length() - 1);
                out.append(template, start, end + 1);
            } else {
                String source = template.substring(start + 2, end).trim();
                Object value;
                try {
                    value = Expressions.evaluate(source, context);
                } catch (ExpressionException ex) {
                    throw new ExpressionException(ErrorCode.TEMPLATE_RENDER_ERROR, template, start,
                            "cannot render placeholder '${" + source + "}': " + ex.getMessage(), ex);
                }
                out.append(Evaluator.stringify(value));
            }
            i = end + 1;
        }
        return out.toString();
    }

    /**
     * Extracts the paths referenced by a template. Used by definition linting to warn when a
     * prompt refers to a node that does not exist in the graph.
     */
    public static List<String> referencedPaths(String template) {
        List<String> paths = new ArrayList<>();
        if (template == null) {
            return paths;
        }
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                break;
            }
            boolean escaped = start > 0 && template.charAt(start - 1) == '\\';
            int end = closingBrace(template, start + 2);
            if (end < 0) {
                break;
            }
            if (!escaped) {
                paths.add(template.substring(start + 2, end).trim());
            }
            i = end + 1;
        }
        return paths;
    }

    /** Finds the matching closing brace, skipping braces that appear inside string literals. */
    private static int closingBrace(String template, int from) {
        char quote = 0;
        for (int i = from; i < template.length(); i++) {
            char c = template.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '}') {
                return i;
            }
        }
        return -1;
    }
}
