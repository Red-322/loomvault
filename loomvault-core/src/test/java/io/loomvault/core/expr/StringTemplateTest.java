package io.loomvault.core.expr;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.ExpressionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@code ${...}} interpolation.
 *
 * <p>The totality rule mirrors the evaluator: an unknown path renders as an empty string, while a
 * malformed template is a configuration error and fails fast.</p>
 */
class StringTemplateTest {

    private static final Map<String, Object> CONTEXT = Map.of(
            "inputs", Map.of("name", "Ada", "priority", "P1"),
            "nodes", Map.of("score", Map.of("overall", 7.5d)));

    @Test
    @DisplayName("interpolates a single placeholder")
    void shouldInterpolateSinglePlaceholder() {
        assertThat(StringTemplate.render("Hello ${inputs.name}", CONTEXT)).isEqualTo("Hello Ada");
    }

    @Test
    @DisplayName("interpolates multiple placeholders in one template")
    void shouldInterpolateMultiplePlaceholders() {
        assertThat(StringTemplate.render("${inputs.name} / ${inputs.priority}", CONTEXT))
                .isEqualTo("Ada / P1");
    }

    @Test
    @DisplayName("interpolates numeric values using their natural text form")
    void shouldInterpolateNumbers() {
        assertThat(StringTemplate.render("score=${nodes.score.overall}", CONTEXT))
                .isEqualTo("score=7.5");
    }

    @Test
    @DisplayName("returns an empty string for a null or empty template")
    void shouldHandleNullOrEmptyTemplate() {
        assertThat(StringTemplate.render(null, CONTEXT)).isEmpty();
        assertThat(StringTemplate.render("", CONTEXT)).isEmpty();
    }

    @Test
    @DisplayName("renders without placeholders unchanged")
    void shouldReturnPlainTextUnchanged() {
        assertThat(StringTemplate.render("no placeholders here", CONTEXT))
                .isEqualTo("no placeholders here");
    }

    @Test
    @DisplayName("renders an unknown path as an empty string instead of failing")
    void shouldRenderUnknownPathAsEmptyString() {
        assertThat(StringTemplate.render("[${missing.path}]", CONTEXT)).isEqualTo("[]");
    }

    @Test
    @DisplayName("treats a backslash-escaped placeholder as a literal")
    void shouldSupportEscapedPlaceholder() {
        assertThat(StringTemplate.render("\\${inputs.name}", CONTEXT)).isEqualTo("${inputs.name}");
        // A mixed template: the escaped one stays literal, the plain one interpolates.
        assertThat(StringTemplate.render("\\${literal} and ${inputs.name}", CONTEXT))
                .isEqualTo("${literal} and Ada");
    }

    @Test
    @DisplayName("fails fast with TEMPLATE_RENDER_ERROR on an unterminated placeholder")
    void shouldThrowOnUnterminatedPlaceholder() {
        assertThatThrownBy(() -> StringTemplate.render("broken ${inputs.name", CONTEXT))
                .isInstanceOfSatisfying(ExpressionException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TEMPLATE_RENDER_ERROR));
    }

    @Test
    @DisplayName("fails fast when the placeholder body is not a valid expression")
    void shouldThrowOnInvalidPlaceholderExpression() {
        assertThatThrownBy(() -> StringTemplate.render("${1 +}", CONTEXT))
                .isInstanceOfSatisfying(ExpressionException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TEMPLATE_RENDER_ERROR));
    }

    @Test
    @DisplayName("extracts referenced paths in order")
    void shouldExtractReferencedPaths() {
        List<String> paths = StringTemplate.referencedPaths(
                "Review ${nodes.score.overall} for ${inputs.name}");
        assertThat(paths).containsExactly("nodes.score.overall", "inputs.name");
    }

    @Test
    @DisplayName("ignores escaped placeholders and returns empty for a null template")
    void shouldIgnoreEscapedPathsWhenExtracting() {
        assertThat(StringTemplate.referencedPaths("\\${not.a.path} ${real.path}"))
                .containsExactly("real.path");
        assertThat(StringTemplate.referencedPaths(null)).isEmpty();
    }
}
