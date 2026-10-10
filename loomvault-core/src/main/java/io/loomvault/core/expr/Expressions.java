package io.loomvault.core.expr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public façade of the expression engine.
 *
 * <p>Parsed ASTs are cached: workflow definitions live for months and are evaluated on every
 * execution, so re-parsing the same condition string a few hundred thousand times would be a
 * silly tax. The cache is bounded to keep a pathological definition from eating the heap.</p>
 */
public final class Expressions {

    private static final int CACHE_LIMIT = 4096;
    private static final ConcurrentHashMap<String, Expression> CACHE = new ConcurrentHashMap<>();

    private Expressions() {
    }

    /**
     * Parses and validates an expression, memoising the result.
     *
     * @throws io.loomvault.core.error.ExpressionException on syntax errors or unknown functions
     */
    public static Expression parse(String source) {
        Expression cached = CACHE.get(source);
        if (cached != null) {
            return cached;
        }
        Expression parsed = ExpressionParser.parse(source);
        Evaluator.validateFunctions(parsed, source);
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(source, parsed);
        return parsed;
    }

    /** Evaluates an expression against a context, returning {@code null} for absent data. */
    public static Object evaluate(String source, Map<String, Object> context) {
        return Evaluator.evaluate(parse(source), context == null ? Map.of() : context);
    }

    /**
     * Evaluates an expression in boolean position.
     *
     * <p>{@code null} and unparsable data are both {@code false} — routing must always terminate.
     * Syntax errors, however, are configuration bugs and are raised loudly at parse time.</p>
     */
    public static boolean evaluateBoolean(String source, Map<String, Object> context) {
        return Evaluator.asBoolean(evaluate(source, context));
    }

    /** Returns {@code true} when the expression parses and uses only known functions. */
    public static boolean isValid(String source) {
        try {
            parse(source);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Clears the parse cache. Only used by tests and long-running operator tooling. */
    public static void clearCache() {
        CACHE.clear();
    }
}
