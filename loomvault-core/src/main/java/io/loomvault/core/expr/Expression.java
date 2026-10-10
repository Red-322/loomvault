package io.loomvault.core.expr;

import java.util.List;

/**
 * Abstract syntax tree of the tiny expression language used by edge conditions and prompt
 * templates.
 *
 * <p>The grammar is deliberately small so that it can be parsed and evaluated in microseconds,
 * has zero third-party dependencies, and — most importantly — is <em>total</em>: evaluation never
 * throws on missing data. A condition that references an absent field evaluates to {@code null},
 * and {@code null} is treated as {@code false} in boolean position (SQL-style three-valued
 * logic). Routing decisions therefore always terminate, even when an upstream prompt produced
 * something unexpected.</p>
 *
 * <h3>Supported syntax</h3>
 * <pre>
 *   literals     42, 3.14, "text", 'text', true, false, null
 *   paths        inputs.ticket.id, nodes.score.overall, items[0].name
 *   arithmetic   + - * / %
 *   comparison   == != &gt; &gt;= &lt; &lt;=
 *   logic        &amp;&amp; || !
 *   functions    len(x), contains(a, b), startsWith(s, p), lower(s), coalesce(a, b), ...
 *   grouping     ( ... )
 * </pre>
 */
public sealed interface Expression {

    /** A constant: number, string, boolean or {@code null}. */
    record Literal(Object value) implements Expression {
    }

    /** A bare name resolved against the evaluation context. */
    record Identifier(String name) implements Expression {
    }

    /** {@code target.field} */
    record FieldAccess(Expression target, String field) implements Expression {
    }

    /** {@code target[index]} — works for lists (numeric index) and maps (string key). */
    record IndexAccess(Expression target, Expression index) implements Expression {
    }

    /** {@code !operand} or {@code -operand}. */
    record Unary(String operator, Expression operand) implements Expression {
    }

    /** {@code left OP right}. */
    record Binary(String operator, Expression left, Expression right) implements Expression {
    }

    /** {@code name(arg, ...)} resolved against the built-in function table. */
    record Call(String function, List<Expression> arguments) implements Expression {
        public Call {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }
    }
}
