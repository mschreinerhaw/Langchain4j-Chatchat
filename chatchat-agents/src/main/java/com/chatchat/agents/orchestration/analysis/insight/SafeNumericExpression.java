package com.chatchat.agents.orchestration.analysis.insight;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

/** Tiny arithmetic-only evaluator: identifiers, decimal numbers and + - * / parentheses. */
public final class SafeNumericExpression {
    private static final MathContext MATH = MathContext.DECIMAL64;
    private final String input;
    private final Map<String, BigDecimal> variables;
    private int position;

    private SafeNumericExpression(String input, Map<String, BigDecimal> variables) {
        this.input = input == null ? "" : input;
        this.variables = variables == null ? Map.of() : variables;
    }

    public static BigDecimal evaluate(String expression, Map<String, BigDecimal> variables) {
        if (expression == null || expression.isBlank() || expression.length() > 500) {
            throw new IllegalArgumentException("A bounded numeric expression is required");
        }
        SafeNumericExpression parser = new SafeNumericExpression(expression, variables);
        BigDecimal value = parser.expression();
        parser.space();
        if (parser.position != parser.input.length()) throw new IllegalArgumentException("Unsupported expression token");
        return value;
    }

    private BigDecimal expression() {
        BigDecimal value = term();
        while (true) {
            space();
            if (take('+')) value = value.add(term(), MATH);
            else if (take('-')) value = value.subtract(term(), MATH);
            else return value;
        }
    }

    private BigDecimal term() {
        BigDecimal value = factor();
        while (true) {
            space();
            if (take('*')) value = value.multiply(factor(), MATH);
            else if (take('/')) {
                BigDecimal divisor = factor();
                if (divisor.compareTo(BigDecimal.ZERO) == 0) throw new IllegalArgumentException("Division by zero");
                value = value.divide(divisor, MATH);
            } else return value;
        }
    }

    private BigDecimal factor() {
        space();
        if (take('-')) return factor().negate(MATH);
        if (take('+')) return factor();
        if (take('(')) {
            BigDecimal value = expression();
            space();
            if (!take(')')) throw new IllegalArgumentException("Unclosed expression group");
            return value;
        }
        if (position < input.length() && (Character.isDigit(input.charAt(position)) || input.charAt(position) == '.')) {
            int start = position++;
            while (position < input.length()
                && (Character.isDigit(input.charAt(position)) || input.charAt(position) == '.')) position++;
            return new BigDecimal(input.substring(start, position), MATH);
        }
        int start = position;
        while (position < input.length()) {
            char value = input.charAt(position);
            if (!(Character.isLetterOrDigit(value) || value == '_' || value == '.')) break;
            position++;
        }
        if (start == position) throw new IllegalArgumentException("Numeric value expected");
        String identifier = input.substring(start, position);
        BigDecimal value = variables.get(identifier);
        if (value == null) throw new IllegalArgumentException("Unknown semantic identifier: " + identifier);
        return value;
    }

    private boolean take(char expected) {
        if (position < input.length() && input.charAt(position) == expected) { position++; return true; }
        return false;
    }

    private void space() { while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++; }
}
