package com.chatchat.agents.orchestration.workflow;

import java.util.Locale;
import java.util.Map;

/** Deterministic evaluator for the deliberately small Runtime OS workflow-condition language. */
public final class WorkflowConditionEvaluator {

    public boolean matches(String condition, Map<String, Object> context) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String expression = condition.trim();
        String[] operators = {">=", "<=", "==", "!=", ">", "<"};
        for (String operator : operators) {
            int index = expression.indexOf(operator);
            if (index <= 0) {
                continue;
            }
            String left = expression.substring(0, index).trim();
            String right = expression.substring(index + operator.length()).trim();
            return compare(value(context, left), operator, right);
        }
        return booleanValue(value(context, expression));
    }

    private Object value(Map<String, Object> context, String key) {
        if (context == null || context.isEmpty() || key == null) {
            return null;
        }
        Object direct = context.get(key);
        return direct == null ? context.get(normalizeKey(key)) : direct;
    }

    private boolean compare(Object leftValue, String operator, String rightText) {
        if (leftValue == null) {
            return false;
        }
        Double leftNumber = number(leftValue);
        Double rightNumber = number(unquote(rightText));
        if (leftNumber != null && rightNumber != null) {
            return switch (operator) {
                case ">=" -> leftNumber >= rightNumber;
                case "<=" -> leftNumber <= rightNumber;
                case ">" -> leftNumber > rightNumber;
                case "<" -> leftNumber < rightNumber;
                case "==" -> leftNumber.doubleValue() == rightNumber.doubleValue();
                case "!=" -> leftNumber.doubleValue() != rightNumber.doubleValue();
                default -> false;
            };
        }
        String left = String.valueOf(leftValue);
        String right = unquote(rightText);
        int comparison = left.compareTo(right);
        return switch (operator) {
            case "==" -> left.equals(right);
            case "!=" -> !left.equals(right);
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            default -> false;
        };
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool
            : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String unquote(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if ((text.startsWith("\"") && text.endsWith("\""))
            || (text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
