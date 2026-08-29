package com.chatchat.agents.runtime.plan.selection;

import com.chatchat.common.tool.ToolOutput;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a read-only retrieval result against a tool-published quality contract.
 *
 * <p>The gate never changes evidence. It only decides whether one execution with
 * the original retrieval arguments is warranted.</p>
 */
public final class RetrievalQualityGate {

    private RetrievalQualityGate() {
    }

    public static Evaluation evaluate(ToolOutput output, Map<String, Object> contract) {
        boolean success = output != null && output.isSuccess();
        Object data = output == null ? null : output.getData();
        List<String> countPaths = strings(contract == null ? null : contract.get("countPaths"));
        long count = maxCount(data, countPaths);
        long minimum = longValue(contract == null ? null : contract.get("minimumResultCount"), 1L);
        boolean sufficient = success && count >= minimum;
        return new Evaluation(success, count, minimum, sufficient,
            success ? (sufficient ? "threshold_met" : "below_result_threshold") : "tool_failed");
    }

    public static Evaluation evaluate(Object data,
                                      boolean success,
                                      Map<String, Object> contract) {
        List<String> countPaths = strings(contract == null ? null : contract.get("countPaths"));
        long count = maxCount(data, countPaths);
        long minimum = longValue(contract == null ? null : contract.get("minimumResultCount"), 1L);
        boolean sufficient = success && count >= minimum;
        return new Evaluation(success, count, minimum, sufficient,
            success ? (sufficient ? "threshold_met" : "below_result_threshold") : "tool_failed");
    }

    public static boolean preferFallback(Evaluation enhanced, Evaluation fallback) {
        if (fallback == null) {
            return false;
        }
        if (enhanced == null) {
            return true;
        }
        if (fallback.success() != enhanced.success()) {
            return fallback.success();
        }
        return fallback.resultCount() > enhanced.resultCount();
    }

    public static Map<String, Object> report(Evaluation enhanced,
                                             Evaluation fallback,
                                             boolean fallbackSelected) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("enabled", true);
        report.put("strategy", "enhanced_then_original_on_below_threshold");
        report.put("fallbackExecuted", fallback != null);
        report.put("selected", fallbackSelected ? "original" : "enhanced");
        report.put("enhanced", enhanced == null ? Map.of() : enhanced.asMap());
        if (fallback != null) {
            report.put("original", fallback.asMap());
        }
        return Map.copyOf(report);
    }

    private static long maxCount(Object data, List<String> paths) {
        long result = 0L;
        for (String path : paths) {
            result = Math.max(result, count(valueAtPath(data, path)));
        }
        if (result == 0L && paths.isEmpty()) {
            result = count(data);
        }
        return result;
    }

    private static Object valueAtPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private static long count(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value.getClass().isArray()) {
            return Array.getLength(value);
        }
        if (value instanceof CharSequence text) {
            try {
                return Math.max(0L, Long.parseLong(text.toString().trim()));
            } catch (NumberFormatException ignored) {
                return text.isEmpty() ? 0L : 1L;
            }
        }
        return 1L;
    }

    private static List<String> strings(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
            return List.copyOf(result);
        }
        return value == null || String.valueOf(value).isBlank()
            ? List.of()
            : List.of(String.valueOf(value));
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record Evaluation(
        boolean success,
        long resultCount,
        long minimumResultCount,
        boolean sufficient,
        String reason
    ) {
        Map<String, Object> asMap() {
            return Map.of(
                "success", success,
                "resultCount", resultCount,
                "minimumResultCount", minimumResultCount,
                "sufficient", sufficient,
                "reason", reason
            );
        }
    }
}
