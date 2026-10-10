package io.loomvault.core.expr;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.ExpressionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for the hand-written expression engine.
 *
 * <p>The engine's contract is <em>totality</em>: evaluation never throws on missing data, and
 * {@code null} propagates through arithmetic while comparisons degrade to {@code false}. These
 * tests pin both the happy path (precedence, functions) and that contract.</p>
 */
class ExpressionEngineTest {

    private static final Map<String, Object> CONTEXT = Map.of(
            "a", Map.of("b", Map.of("c", 42L)),
            "items", List.of("zero", "one", "two"),
            "name", "Ada");

    // -----------------------------------------------------------------------------------------
    // Arithmetic, comparison, logic
    // -----------------------------------------------------------------------------------------

    static Stream<Arguments> arithmeticLogicAndComparisonCases() {
        return Stream.of(
                Arguments.of("1 + 2", 3L),
                Arguments.of("7 - 2", 5L),
                Arguments.of("2 * 3", 6L),
                // Precedence: multiplication binds tighter than addition.
                Arguments.of("1 + 2 * 3", 7L),
                Arguments.of("(1 + 2) * 3", 9L),
                Arguments.of("10 / 4", 2.5d),
                Arguments.of("10 % 3", 1.0d),
                Arguments.of("-5", -5L),
                Arguments.of("2 + 3 == 5", true),
                Arguments.of("3 > 2", true),
                Arguments.of("3 >= 3", true),
                Arguments.of("2 < 1", false),
                Arguments.of("1 != 2", true),
                // String concatenation wins whenever either operand is textual.
                Arguments.of("\"a\" + \"b\"", "ab"),
                Arguments.of("\"count: \" + 3", "count: 3"),
                Arguments.of("!false", true),
                Arguments.of("!true", false),
                // Short-circuiting: the right-hand side is never evaluated.
                Arguments.of("true || (1 / 0 == 1)", true),
                Arguments.of("false && (1 / 0 == 1)", false));
    }

    @ParameterizedTest(name = "shouldEvaluate [{0}] to {1}")
    @MethodSource("arithmeticLogicAndComparisonCases")
    @DisplayName("evaluates arithmetic, comparison and logic with correct precedence")
    void shouldEvaluateArithmeticLogicAndComparison(String source, Object expected) {
        assertThat(Expressions.evaluate(source, CONTEXT)).isEqualTo(expected);
    }

    // -----------------------------------------------------------------------------------------
    // Three-valued (SQL-style) logic
    // -----------------------------------------------------------------------------------------

    static Stream<Arguments> threeValuedLogicCases() {
        return Stream.of(
                // Arithmetic with a missing operand yields null rather than throwing.
                Arguments.of("null + 1", null),
                Arguments.of("1 + null", null),
                Arguments.of("null * 2", null),
                Arguments.of("-null", null),
                // Division and modulo by zero are null, never an exception.
                Arguments.of("1 / 0", null),
                Arguments.of("1 % 0", null),
                // Comparisons against null are false, which keeps routing total.
                Arguments.of("null > 1", false),
                Arguments.of("1 <= null", false),
                // Equality still works with null.
                Arguments.of("null == null", true),
                Arguments.of("null != 1", true));
    }

    @ParameterizedTest(name = "shouldTreat [{0}] as {1}")
    @MethodSource("threeValuedLogicCases")
    @DisplayName("applies three-valued logic: null propagates, division by zero is null")
    void shouldApplyThreeValuedLogic(String source, Object expected) {
        assertThat(Expressions.evaluate(source, CONTEXT)).isEqualTo(expected);
    }

    @Test
    @DisplayName("treats null in boolean position as false")
    void shouldTreatNullAsFalseInBooleanPosition() {
        assertThat(Expressions.evaluateBoolean("null", CONTEXT)).isFalse();
        assertThat(Expressions.evaluateBoolean("1 / 0", CONTEXT)).isFalse();
        assertThat(Expressions.evaluateBoolean("missing.path", CONTEXT)).isFalse();
    }

    @Test
    @DisplayName("treats empty and zero-like strings as false in boolean position")
    void shouldTreatEmptyStringAsFalse() {
        assertThat(Expressions.evaluateBoolean("\"\"", CONTEXT)).isFalse();
        assertThat(Expressions.evaluateBoolean("\"false\"", CONTEXT)).isFalse();
        assertThat(Expressions.evaluateBoolean("\"yes\"", CONTEXT)).isTrue();
        assertThat(Expressions.evaluateBoolean("0", CONTEXT)).isFalse();
        assertThat(Expressions.evaluateBoolean("1", CONTEXT)).isTrue();
    }

    // -----------------------------------------------------------------------------------------
    // Paths and indexes
    // -----------------------------------------------------------------------------------------

    static Stream<Arguments> pathAndIndexCases() {
        return Stream.of(
                Arguments.of("a.b.c", 42L),
                Arguments.of("name", "Ada"),
                Arguments.of("items[0]", "zero"),
                Arguments.of("items[2]", "two"),
                // Out-of-range and missing paths are null, not errors.
                Arguments.of("items[9]", null),
                Arguments.of("missing.path", null),
                Arguments.of("a.b.c.d", null));
    }

    @ParameterizedTest(name = "shouldResolve [{0}] to {1}")
    @MethodSource("pathAndIndexCases")
    @DisplayName("resolves dotted paths and array indexes, returning null when absent")
    void shouldResolvePathsAndIndexes(String source, Object expected) {
        assertThat(Expressions.evaluate(source, CONTEXT)).isEqualTo(expected);
    }

    // -----------------------------------------------------------------------------------------
    // Built-in functions
    // -----------------------------------------------------------------------------------------

    static Stream<Arguments> builtinFunctionCases() {
        return Stream.of(
                Arguments.of("len(\"hello\")", 5L),
                Arguments.of("len(items)", 3L),
                Arguments.of("contains(\"hello\", \"ell\")", true),
                Arguments.of("contains(\"hello\", \"xyz\")", false),
                Arguments.of("coalesce(null, \"fallback\")", "fallback"),
                Arguments.of("coalesce(\"first\", \"second\")", "first"),
                Arguments.of("coalesce(missing.path, null, \"last\")", "last"),
                Arguments.of("round(3.14159, 2)", 3.14d),
                Arguments.of("round(2.5)", 3.0d),
                Arguments.of("upper(\"abc\")", "ABC"),
                Arguments.of("lower(\"ABC\")", "abc"),
                Arguments.of("json(1)", "1"),
                Arguments.of("json(null)", "null"),
                Arguments.of("toNumber(\"42\")", 42.0d),
                Arguments.of("isEmpty(\"\" )", true));
    }

    @ParameterizedTest(name = "shouldInvoke [{0}] -> {1}")
    @MethodSource("builtinFunctionCases")
    @DisplayName("evaluates the documented built-in functions")
    void shouldEvaluateBuiltinFunctions(String source, Object expected) {
        assertThat(Expressions.evaluate(source, CONTEXT)).isEqualTo(expected);
    }

    // -----------------------------------------------------------------------------------------
    // Parse-time failures
    // -----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("parse-time validation")
    class ParseTimeValidation {

        @Test
        @DisplayName("rejects an incomplete expression with EXPRESSION_SYNTAX_ERROR")
        void shouldRejectIncompleteExpression() {
            assertThatThrownBy(() -> Expressions.parse("1 +"))
                    .isInstanceOfSatisfying(ExpressionException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.EXPRESSION_SYNTAX_ERROR));
        }

        @Test
        @DisplayName("rejects an unbalanced parenthesis")
        void shouldRejectUnbalancedParenthesis() {
            assertThatThrownBy(() -> Expressions.parse("(1 + 2"))
                    .isInstanceOf(ExpressionException.class);
        }

        @Test
        @DisplayName("rejects an unterminated string literal")
        void shouldRejectUnterminatedString() {
            assertThatThrownBy(() -> Expressions.parse("\"unterminated"))
                    .isInstanceOf(ExpressionException.class);
        }

        @Test
        @DisplayName("rejects a blank expression")
        void shouldRejectBlankExpression() {
            assertThatThrownBy(() -> Expressions.parse("   "))
                    .isInstanceOf(ExpressionException.class);
        }

        @Test
        @DisplayName("rejects an unknown function at parse time, not at evaluation time")
        void shouldRejectUnknownFunctionAtParseTime() {
            assertThatThrownBy(() -> Expressions.parse("totallyUnknownFn(1)"))
                    .isInstanceOfSatisfying(ExpressionException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.EXPRESSION_SYNTAX_ERROR));
        }

        @Test
        @DisplayName("reports validity through isValid without throwing")
        void shouldReportValidity() {
            assertThat(Expressions.isValid("1 + 2")).isTrue();
            assertThat(Expressions.isValid("totallyUnknownFn(1)")).isFalse();
            assertThat(Expressions.isValid("1 +")).isFalse();
        }
    }
}
