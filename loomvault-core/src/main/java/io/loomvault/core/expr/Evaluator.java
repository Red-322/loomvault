package io.loomvault.core.expr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.ExpressionException;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Tree-walking evaluator with SQL-style three-valued logic.
 *
 * <p>Design contract — <b>evaluation is total</b>:</p>
 * <ul>
 *   <li>any operation involving {@code null} yields {@code null}, except {@code ==}, {@code !=}
 *       and the short-circuiting {@code &&}/{@code ||};</li>
 *   <li>{@code null} in boolean position is {@code false};</li>
 *   <li>division and modulo by zero yield {@code null} instead of throwing;</li>
 *   <li>missing fields and out-of-range indexes yield {@code null}.</li>
 * </ul>
 * <p>The only failures that escape are unknown functions — and those are rejected at parse time
 * by {@link Expressions#parse(String)}, so a misconfigured workflow is caught when it is loaded,
 * never in the middle of a production run.</p>
 */
public final class Evaluator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Evaluator() {
    }

    // =========================================================================================
    // Core dispatch
    // =========================================================================================

    static Object evaluate(Expression expression, Map<String, Object> context) {
        return switch (expression) {
            case Expression.Literal literal -> literal.value();
            case Expression.Identifier identifier -> context.get(identifier.name());
            case Expression.FieldAccess access -> readField(evaluate(access.target(), context), access.field());
            case Expression.IndexAccess access -> readIndex(evaluate(access.target(), context), evaluate(access.index(), context));
            case Expression.Unary unary -> evaluateUnary(unary, context);
            case Expression.Binary binary -> evaluateBinary(binary, context);
            case Expression.Call call -> callFunction(call, context);
        };
    }

    /** Walks the AST and rejects calls to functions that do not exist. */
    static void validateFunctions(Expression expression, String source) {
        switch (expression) {
            case Expression.Call call -> {
                if (!Functions.exists(call.function())) {
                    throw new ExpressionException(ErrorCode.EXPRESSION_SYNTAX_ERROR, source, -1,
                            "unknown function '" + call.function() + "' in expression; available: " + Functions.names());
                }
                call.arguments().forEach(arg -> validateFunctions(arg, source));
            }
            case Expression.Unary unary -> validateFunctions(unary.operand(), source);
            case Expression.Binary binary -> {
                validateFunctions(binary.left(), source);
                validateFunctions(binary.right(), source);
            }
            case Expression.FieldAccess access -> validateFunctions(access.target(), source);
            case Expression.IndexAccess access -> {
                validateFunctions(access.target(), source);
                validateFunctions(access.index(), source);
            }
            case Expression.Literal ignored -> {
                // leaf
            }
            case Expression.Identifier ignored -> {
                // leaf
            }
        }
    }

    // =========================================================================================
    // Accessors
    // =========================================================================================

    static Object readField(Object target, String field) {
        if (target == null) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(field);
        }
        return null;
    }

    static Object readIndex(Object target, Object index) {
        if (target == null || index == null) {
            return null;
        }
        if (target instanceof List<?> list) {
            Double numeric = asDouble(index);
            if (numeric == null) {
                return null;
            }
            int i = numeric.intValue();
            return (i >= 0 && i < list.size()) ? list.get(i) : null;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(String.valueOf(index));
        }
        if (target.getClass().isArray()) {
            Double numeric = asDouble(index);
            if (numeric == null) {
                return null;
            }
            int i = numeric.intValue();
            return (i >= 0 && i < Array.getLength(target)) ? Array.get(target, i) : null;
        }
        return null;
    }

    // =========================================================================================
    // Unary & binary operators
    // =========================================================================================

    private static Object evaluateUnary(Expression.Unary unary, Map<String, Object> context) {
        Object operand = evaluate(unary.operand(), context);
        return switch (unary.operator()) {
            case "!" -> !asBoolean(operand);
            case "-" -> operand == null ? null : negate(operand);
            default -> throw new ExpressionException(ErrorCode.EXPRESSION_EVALUATION_ERROR,
                    unary.operator(), -1, "unsupported unary operator '" + unary.operator() + "'");
        };
    }

    private static Object evaluateBinary(Expression.Binary binary, Map<String, Object> context) {
        String op = binary.operator();

        // Short-circuiting logic first: operand evaluation is lazy by design, so a slow or
        // unsafe right-hand side never runs when the left side already decides the outcome.
        if (op.equals("&&")) {
            return asBoolean(evaluate(binary.left(), context)) && asBoolean(evaluate(binary.right(), context));
        }
        if (op.equals("||")) {
            return asBoolean(evaluate(binary.left(), context)) || asBoolean(evaluate(binary.right(), context));
        }

        Object left = evaluate(binary.left(), context);
        Object right = evaluate(binary.right(), context);

        return switch (op) {
            case "==" -> deepEquals(left, right);
            case "!=" -> !deepEquals(left, right);
            case ">", ">=", "<", "<=" -> compare(op, left, right);
            case "+" -> add(left, right);
            case "-", "*", "/", "%" -> arithmetic(op, left, right);
            default -> throw new ExpressionException(ErrorCode.EXPRESSION_EVALUATION_ERROR,
                    binary.operator(), -1, "unsupported operator '" + op + "'");
        };
    }

    private static Object compare(String op, Object left, Object right) {
        Integer cmp = compareValues(left, right);
        if (cmp == null) {
            // Null or incomparable operands: comparison is false, never an exception.
            return Boolean.FALSE;
        }
        return switch (op) {
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            default -> cmp <= 0;
        };
    }

    private static Object add(Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        // String concatenation wins whenever either side is textual.
        if (left instanceof String || right instanceof String) {
            return stringify(left) + stringify(right);
        }
        Double l = asDouble(left);
        Double r = asDouble(right);
        if (l == null || r == null) {
            return null;
        }
        if (isIntegral(left) && isIntegral(right)) {
            return (long) (l + r);
        }
        return l + r;
    }

    private static Object arithmetic(String op, Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        Double l = asDouble(left);
        Double r = asDouble(right);
        if (l == null || r == null) {
            return null;
        }
        switch (op) {
            case "-" -> {
                return (isIntegral(left) && isIntegral(right)) ? (Object) (long) (l - r) : (Object) (l - r);
            }
            case "*" -> {
                return (isIntegral(left) && isIntegral(right)) ? (Object) (long) (l * r) : (Object) (l * r);
            }
            case "/" -> {
                return r == 0d ? null : l / r;
            }
            case "%" -> {
                return r == 0d ? null : l % r;
            }
            default -> {
                return null;
            }
        }
    }

    private static Object negate(Object value) {
        Double numeric = asDouble(value);
        if (numeric == null) {
            return null;
        }
        return isIntegral(value) ? (Object) (long) -numeric : (Object) (-numeric);
    }

    // =========================================================================================
    // Functions
    // =========================================================================================

    private static Object callFunction(Expression.Call call, Map<String, Object> context) {
        List<Object> args = call.arguments().stream().map(a -> evaluate(a, context)).toList();
        return Functions.invoke(call.function(), args);
    }

    /** Built-in function table: small, documented, and testable in isolation. */
    static final class Functions {

        private static final List<String> NAMES = List.of(
                "len", "isEmpty", "isNull", "isNotNull", "contains", "startsWith", "endsWith",
                "lower", "upper", "trim", "abs", "min", "max", "round", "coalesce",
                "toNumber", "toText", "json");

        static boolean exists(String name) {
            return NAMES.contains(name);
        }

        static List<String> names() {
            return NAMES;
        }

        static Object invoke(String name, List<Object> args) {
            switch (name) {
                case "len" -> {
                    Object v = arg(args, 0);
                    if (v == null) {
                        return 0L;
                    }
                    if (v instanceof String s) {
                        return (long) s.length();
                    }
                    if (v instanceof Collection<?> c) {
                        return (long) c.size();
                    }
                    if (v instanceof Map<?, ?> m) {
                        return (long) m.size();
                    }
                    if (v.getClass().isArray()) {
                        return (long) Array.getLength(v);
                    }
                    return (long) stringify(v).length();
                }
                case "isEmpty" -> {
                    Object v = arg(args, 0);
                    if (v == null) {
                        return Boolean.TRUE;
                    }
                    if (v instanceof String s) {
                        return s.isEmpty();
                    }
                    if (v instanceof Collection<?> c) {
                        return c.isEmpty();
                    }
                    if (v instanceof Map<?, ?> m) {
                        return m.isEmpty();
                    }
                    return Boolean.FALSE;
                }
                case "isNull" -> {
                    return arg(args, 0) == null;
                }
                case "isNotNull" -> {
                    return arg(args, 0) != null;
                }
                case "contains" -> {
                    Object haystack = arg(args, 0);
                    Object needle = arg(args, 1);
                    if (haystack == null || needle == null) {
                        return Boolean.FALSE;
                    }
                    if (haystack instanceof String s) {
                        return s.contains(stringify(needle));
                    }
                    if (haystack instanceof Collection<?> c) {
                        return c.stream().anyMatch(item -> deepEquals(item, needle));
                    }
                    if (haystack instanceof Map<?, ?> m) {
                        return m.containsKey(stringify(needle));
                    }
                    return Boolean.FALSE;
                }
                case "startsWith" -> {
                    Object s = arg(args, 0);
                    Object p = arg(args, 1);
                    return s != null && p != null && stringify(s).startsWith(stringify(p));
                }
                case "endsWith" -> {
                    Object s = arg(args, 0);
                    Object p = arg(args, 1);
                    return s != null && p != null && stringify(s).endsWith(stringify(p));
                }
                case "lower" -> {
                    Object s = arg(args, 0);
                    return s == null ? null : stringify(s).toLowerCase();
                }
                case "upper" -> {
                    Object s = arg(args, 0);
                    return s == null ? null : stringify(s).toUpperCase();
                }
                case "trim" -> {
                    Object s = arg(args, 0);
                    return s == null ? null : stringify(s).trim();
                }
                case "abs" -> {
                    Double d = asDouble(arg(args, 0));
                    return d == null ? null : (Object) Math.abs(d);
                }
                case "min" -> {
                    return minMax(args, true);
                }
                case "max" -> {
                    return minMax(args, false);
                }
                case "round" -> {
                    Double d = asDouble(arg(args, 0));
                    if (d == null) {
                        return null;
                    }
                    Double digits = asDouble(arg(args, 1));
                    int scale = digits == null ? 0 : digits.intValue();
                    return BigDecimal.valueOf(d).setScale(scale, RoundingMode.HALF_UP).doubleValue();
                }
                case "coalesce" -> {
                    for (Object v : args) {
                        if (v != null) {
                            return v;
                        }
                    }
                    return null;
                }                case "toNumber" -> {
                    return asDouble(arg(args, 0));
                }
                case "toText" -> {
                    Object v = arg(args, 0);
                    return v == null ? null : stringify(v);
                }
                case "json" -> {
                    Object v = arg(args, 0);
                    if (v == null) {
                        return "null";
                    }
                    try {
                        return JSON.writeValueAsString(v);
                    } catch (Exception ex) {
                        return stringify(v);
                    }
                }
                default -> throw new ExpressionException(ErrorCode.EXPRESSION_EVALUATION_ERROR, name, -1,
                        "unknown function '" + name + "'");
            }
        }

        private static Object minMax(List<Object> args, boolean min) {
            Double best = null;
            for (Object a : args) {
                Double d = asDouble(a);
                if (d == null) {
                    continue;
                }
                if (best == null || (min ? d < best : d > best)) {
                    best = d;
                }
            }
            return best;
        }

        private static Object arg(List<Object> args, int index) {
            return index < args.size() ? args.get(index) : null;
        }

        private Functions() {
        }
    }

    // =========================================================================================
    // Coercion helpers
    // =========================================================================================

    /** Converts a value to boolean following the documented "truthiness" rules. */
    public static boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("false")
                    || trimmed.equals("0") || trimmed.equalsIgnoreCase("no")) {
                return false;
            }
            return true;
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    static boolean isIntegral(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte;
    }

    /** Returns {@code null} when the two values are not comparable at all. */
    static Integer compareValues(Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        Double l = asDouble(left);
        Double r = asDouble(right);
        if (l != null && r != null) {
            return Double.compare(l, r);
        }
        if (left instanceof String ls && right instanceof String rs) {
            return ls.compareTo(rs);
        }
        if (left instanceof Boolean lb && right instanceof Boolean rb) {
            return Boolean.compare(lb, rb);
        }
        return null;
    }

    static boolean deepEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            Double l = asDouble(left);
            Double r = asDouble(right);
            return l != null && l.equals(r);
        }
        if (left instanceof Collection<?> lc && right instanceof Collection<?> rc) {
            if (lc.size() != rc.size()) {
                return false;
            }
            var li = lc.iterator();
            var ri = rc.iterator();
            while (li.hasNext()) {
                if (!deepEquals(li.next(), ri.next())) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Map<?, ?> lm && right instanceof Map<?, ?> rm) {
            if (lm.size() != rm.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : lm.entrySet()) {
                if (!rm.containsKey(e.getKey()) || !deepEquals(e.getValue(), rm.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    /** Renders a value the way templates need it: {@code null} becomes an empty string. */
    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
