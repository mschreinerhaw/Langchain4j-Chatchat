package com.chatchat.mcpserver.http;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates common API response envelopes independently from HTTP transport success.
 */
public final class ApiBusinessResponseEvaluator {

    private static final Set<String> FAILURE_STATUSES = Set.of(
        "false", "fail", "failed", "failure", "error", "exception",
        "invalid", "unauthorized", "forbidden", "denied"
    );
    private static final Set<String> SUCCESS_CODES = Set.of(
        "0", "00", "0000", "000000", "ok", "success", "succeeded"
    );

    private ApiBusinessResponseEvaluator() {
    }

    public static String failure(Object body) {
        if (!(body instanceof Map<?, ?> map)) {
            return null;
        }
        String direct = failureIn(map);
        if (direct != null) {
            return direct;
        }
        Object nested = value(map, "result");
        return nested instanceof Map<?, ?> nestedMap ? failureIn(nestedMap) : null;
    }

    private static String failureIn(Map<?, ?> map) {
        if (isFalse(value(map, "success", "ok", "passed", "available"))) {
            return message(map, "API returned an unsuccessful business result");
        }
        Object status = value(map, "status", "resultStatus");
        if (status != null && FAILURE_STATUSES.contains(text(status).toLowerCase(Locale.ROOT))) {
            return message(map, "API returned business status " + status);
        }
        Object error = value(map, "error", "errors", "errorMessage", "error_message");
        if (meaningfulError(error)) {
            return message(map, text(error));
        }
        for (String field : new String[]{"retu_code", "retuCode", "returnCode", "code"}) {
            Object code = value(map, field);
            if (code != null && !successfulCode(code)) {
                return message(map, "API returned business code " + code) + " (" + field + "=" + code + ")";
            }
        }
        return null;
    }

    private static boolean successfulCode(Object value) {
        String code = text(value);
        if (SUCCESS_CODES.contains(code.toLowerCase(Locale.ROOT))) {
            return true;
        }
        try {
            double number = Double.parseDouble(code);
            return number == 0D || (number >= 200D && number < 300D);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean meaningfulError(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value.getClass().isArray()) {
            return Array.getLength(value) > 0;
        }
        String text = text(value);
        return !text.isBlank() && !"false".equalsIgnoreCase(text)
            && !"null".equalsIgnoreCase(text) && !"none".equalsIgnoreCase(text);
    }

    private static boolean isFalse(Object value) {
        return value instanceof Boolean bool ? !bool : "false".equalsIgnoreCase(text(value));
    }

    private static String message(Map<?, ?> map, String fallback) {
        Object detail = value(map, "errorMessage", "error_message", "error",
            "note", "memo", "message", "msg", "detail", "reason");
        String text = text(detail);
        return text.isBlank() ? fallback : text;
    }

    private static Object value(Map<?, ?> map, String... names) {
        for (String name : names) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && name.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
