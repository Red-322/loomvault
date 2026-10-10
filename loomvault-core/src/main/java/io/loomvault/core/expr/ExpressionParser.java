package io.loomvault.core.expr;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.ExpressionException;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written lexer and recursive-descent parser for the LoomVault expression language.
 *
 * <p>No parser generator, no runtime grammar table: ~250 lines that fit in one head and can be
 * audited during an interview. Operator precedence, from loosest to tightest:</p>
 * <pre>
 *   ||  &gt;  &amp;&amp;  &gt;  == !=  &gt;  &gt;= &lt;= &gt; &lt;  &gt;  + -  &gt;  * / %  &gt;  unary ! -  &gt;  postfix . []  &gt;  primary
 * </pre>
 */
public final class ExpressionParser {

    private ExpressionParser() {
    }

    /** Parses {@code source} into an AST. Throws {@link ExpressionException} on syntax errors. */
    public static Expression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new ExpressionException(ErrorCode.EXPRESSION_SYNTAX_ERROR, source, -1,
                    "expression must not be blank");
        }
        List<Token> tokens = Lexer.tokenize(source);
        Parser parser = new Parser(source, tokens);
        Expression expression = parser.parseExpression();
        parser.expect(TokenType.EOF, "unexpected trailing input");
        return expression;
    }

    // =========================================================================================
    // Lexer
    // =========================================================================================

    enum TokenType {
        NUMBER, STRING, IDENT, TRUE, FALSE, NULL, OPERATOR, LPAREN, RPAREN, LBRACKET, RBRACKET, DOT, COMMA, EOF
    }

    record Token(TokenType type, String text, Object literal, int offset) {
    }

    /** Splits raw text into tokens; supports escapes inside single- and double-quoted strings. */
    static final class Lexer {

        private static final String[] OPERATORS = {"||", "&&", "==", "!=", ">=", "<=", ">", "<", "+", "-", "*", "/", "%", "!"};

        private Lexer() {
        }

        static List<Token> tokenize(String src) {
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            int n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                int start = i;

                if (c == '(') {
                    tokens.add(new Token(TokenType.LPAREN, "(", null, start));
                    i++;
                } else if (c == ')') {
                    tokens.add(new Token(TokenType.RPAREN, ")", null, start));
                    i++;
                } else if (c == '[') {
                    tokens.add(new Token(TokenType.LBRACKET, "[", null, start));
                    i++;
                } else if (c == ']') {
                    tokens.add(new Token(TokenType.RBRACKET, "]", null, start));
                    i++;
                } else if (c == '.') {
                    tokens.add(new Token(TokenType.DOT, ".", null, start));
                    i++;
                } else if (c == ',') {
                    tokens.add(new Token(TokenType.COMMA, ",", null, start));
                    i++;
                } else if (c == '"' || c == '\'') {
                    i = readString(src, tokens, i, c);
                } else if (Character.isDigit(c)) {
                    i = readNumber(src, tokens, i);
                } else if (Character.isLetter(c) || c == '_' || c == '$') {
                    i = readIdentifier(src, tokens, i);
                } else {
                    String op = matchOperator(src, i);
                    if (op == null) {
                        throw new ExpressionException(ErrorCode.EXPRESSION_SYNTAX_ERROR, src, i,
                                "unexpected character '" + c + "'");
                    }
                    tokens.add(new Token(TokenType.OPERATOR, op, null, start));
                    i += op.length();
                }
            }
            tokens.add(new Token(TokenType.EOF, "", null, n));
            return tokens;
        }

        private static String matchOperator(String src, int offset) {
            for (String op : OPERATORS) {
                if (src.startsWith(op, offset)) {
                    return op;
                }
            }
            return null;
        }

        private static int readString(String src, List<Token> tokens, int start, char quote) {
            StringBuilder sb = new StringBuilder();
            int i = start + 1;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '\\' && i + 1 < src.length()) {
                    char next = src.charAt(i + 1);
                    sb.append(switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '\\' -> '\\';
                        case '"' -> '"';
                        case '\'' -> '\'';
                        default -> next;
                    });
                    i += 2;
                } else if (c == quote) {
                    tokens.add(new Token(TokenType.STRING, sb.toString(), sb.toString(), start));
                    return i + 1;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            throw new ExpressionException(ErrorCode.EXPRESSION_SYNTAX_ERROR, src, start, "unterminated string literal");
        }

        private static int readNumber(String src, List<Token> tokens, int start) {
            int i = start;
            boolean decimal = false;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isDigit(c)) {
                    i++;
                } else if (c == '.' && !decimal && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))) {
                    decimal = true;
                    i++;
                } else {
                    break;
                }
            }
            String text = src.substring(start, i);
            Object value = decimal ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
            tokens.add(new Token(TokenType.NUMBER, text, value, start));
            return i;
        }

        private static int readIdentifier(String src, List<Token> tokens, int start) {
            int i = start;
            while (i < src.length()
                    && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_' || src.charAt(i) == '$')) {
                i++;
            }
            String text = src.substring(start, i);
            TokenType type = switch (text) {
                case "true" -> TokenType.TRUE;
                case "false" -> TokenType.FALSE;
                case "null" -> TokenType.NULL;
                default -> TokenType.IDENT;
            };
            Object literal = switch (type) {
                case TRUE -> Boolean.TRUE;
                case FALSE -> Boolean.FALSE;
                default -> null;
            };
            tokens.add(new Token(type, text, literal, start));
            return i;
        }
    }

    // =========================================================================================
    // Parser
    // =========================================================================================

    private static final class Parser {

        private final String source;
        private final List<Token> tokens;
        private int pos;

        Parser(String source, List<Token> tokens) {
            this.source = source;
            this.tokens = tokens;
        }

        Expression parseExpression() {
            return parseOr();
        }

        private Expression parseOr() {
            Expression left = parseAnd();
            while (matchOperator("||")) {
                left = new Expression.Binary("||", left, parseAnd());
            }
            return left;
        }

        private Expression parseAnd() {
            Expression left = parseEquality();
            while (matchOperator("&&")) {
                left = new Expression.Binary("&&", left, parseEquality());
            }
            return left;
        }

        private Expression parseEquality() {
            Expression left = parseComparison();
            while (true) {
                if (matchOperator("==")) {
                    left = new Expression.Binary("==", left, parseComparison());
                } else if (matchOperator("!=")) {
                    left = new Expression.Binary("!=", left, parseComparison());
                } else {
                    return left;
                }
            }
        }

        private Expression parseComparison() {
            Expression left = parseAdditive();
            while (true) {
                String op = peekOperator();
                if (op != null && switch (op) {
                    case ">", ">=", "<", "<=" -> true;
                    default -> false;
                }) {
                    advance();
                    left = new Expression.Binary(op, left, parseAdditive());
                } else {
                    return left;
                }
            }
        }

        private Expression parseAdditive() {
            Expression left = parseMultiplicative();
            while (true) {
                String op = peekOperator();
                if (op != null && (op.equals("+") || op.equals("-"))) {
                    advance();
                    left = new Expression.Binary(op, left, parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private Expression parseMultiplicative() {
            Expression left = parseUnary();
            while (true) {
                String op = peekOperator();
                if (op != null && (op.equals("*") || op.equals("/") || op.equals("%"))) {
                    advance();
                    left = new Expression.Binary(op, left, parseUnary());
                } else {
                    return left;
                }
            }
        }

        private Expression parseUnary() {
            String op = peekOperator();
            if (op != null && (op.equals("!") || op.equals("-"))) {
                advance();
                return new Expression.Unary(op, parseUnary());
            }
            return parsePostfix();
        }

        private Expression parsePostfix() {
            Expression target = parsePrimary();
            while (true) {
                if (match(TokenType.DOT)) {
                    Token field = expect(TokenType.IDENT, "expected field name after '.'");
                    target = new Expression.FieldAccess(target, field.text());
                } else if (match(TokenType.LBRACKET)) {
                    Expression index = parseExpression();
                    expect(TokenType.RBRACKET, "expected ']' to close index access");
                    target = new Expression.IndexAccess(target, index);
                } else {
                    return target;
                }
            }
        }

        private Expression parsePrimary() {
            Token token = peek();
            switch (token.type()) {
                case NUMBER, STRING -> {
                    advance();
                    return new Expression.Literal(token.literal());
                }
                case TRUE, FALSE -> {
                    advance();
                    return new Expression.Literal(token.literal());
                }
                case NULL -> {
                    advance();
                    return new Expression.Literal(null);
                }
                case IDENT -> {
                    advance();
                    if (match(TokenType.LPAREN)) {
                        List<Expression> args = new ArrayList<>();
                        if (!match(TokenType.RPAREN)) {
                            do {
                                args.add(parseExpression());
                            } while (match(TokenType.COMMA));
                            expect(TokenType.RPAREN, "expected ')' to close argument list");
                        }
                        return new Expression.Call(token.text(), args);
                    }
                    return new Expression.Identifier(token.text());
                }
                case LPAREN -> {
                    advance();
                    Expression inner = parseExpression();
                    expect(TokenType.RPAREN, "expected ')' to close group");
                    return inner;
                }
                default -> throw new ExpressionException(ErrorCode.EXPRESSION_SYNTAX_ERROR, source, token.offset(),
                        "unexpected token '" + token.text() + "'");
            }
        }

        // ---- token helpers ------------------------------------------------------------------

        private Token peek() {
            return tokens.get(pos);
        }

        private void advance() {
            if (pos < tokens.size() - 1) {
                pos++;
            }
        }

        private boolean match(TokenType type) {
            if (peek().type() == type) {
                advance();
                return true;
            }
            return false;
        }

        private String peekOperator() {
            Token t = peek();
            return t.type() == TokenType.OPERATOR ? t.text() : null;
        }

        private boolean matchOperator(String op) {
            if (op.equals(peekOperator())) {
                advance();
                return true;
            }
            return false;
        }

        private Token expect(TokenType type, String message) {
            Token t = peek();
            if (t.type() != type) {
                throw new ExpressionException(ErrorCode.EXPRESSION_SYNTAX_ERROR, source, t.offset(), message);
            }
            advance();
            return t;
        }
    }
}
