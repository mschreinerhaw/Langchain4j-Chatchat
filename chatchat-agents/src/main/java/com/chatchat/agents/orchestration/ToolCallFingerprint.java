package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Produces a semantic-enough fingerprint for read-only plan revision deduplication.
 */
final class ToolCallFingerprint {

    private static final List<String> SEARCH_KEYS = List.of(
        "query", "keyword", "keywords", "search", "searchText", "question", "q"
    );

    private ToolCallFingerprint() {
    }

    static List<String> forPlan(InterpretationPlan plan) {
        if (plan == null || plan.steps() == null) {
            return List.of();
        }
        List<String> fingerprints = new ArrayList<>();
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null || !step.mcpToolAction()) {
                continue;
            }
            fingerprints.add(normalizeTool(step.toolName()) + "::" + canonical(step.input(), null));
        }
        fingerprints.sort(Comparator.naturalOrder());
        return List.copyOf(fingerprints);
    }

    static boolean materiallyEquivalent(InterpretationPlan first, InterpretationPlan second) {
        List<String> left = forPlan(first);
        List<String> right = forPlan(second);
        return !left.isEmpty() && left.equals(right);
    }

    private static String canonical(Object value, String key) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> sorted = new TreeMap<>();
            raw.forEach((mapKey, mapValue) -> {
                if (mapKey != null) {
                    sorted.put(String.valueOf(mapKey), mapValue);
                }
            });
            StringBuilder result = new StringBuilder("{");
            sorted.forEach((mapKey, mapValue) -> result
                .append(normalizeKey(mapKey))
                .append('=')
                .append(canonical(mapValue, mapKey))
                .append(';'));
            return result.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : collection) {
                result.append(canonical(item, key)).append(';');
            }
            return result.append(']').toString();
        }
        String text = String.valueOf(value);
        return isSearchKey(key) ? normalizeSearchText(text) : normalizeValue(text);
    }

    private static boolean isSearchKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return SEARCH_KEYS.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(key));
    }

    private static String normalizeSearchText(String value) {
        return normalizeValue(value)
            .replaceAll("^(请|请帮我|帮我|麻烦)?(查询|查找|检索|搜索)", "")
            .replace("相关内容", "")
            .replace("相关信息", "")
            .replace("的", "")
            .replace("一下", "");
    }

    private static String normalizeTool(String value) {
        return normalizeValue(value).replace('-', '_');
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeValue(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
