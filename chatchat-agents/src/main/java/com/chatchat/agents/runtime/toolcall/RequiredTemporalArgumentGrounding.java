package com.chatchat.agents.runtime.toolcall;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Preflight for schema-declared required dates; model arguments are not evidence. */
public final class RequiredTemporalArgumentGrounding {
    private final Clock clock;

    public RequiredTemporalArgumentGrounding() { this(Clock.systemDefaultZone()); }
    public RequiredTemporalArgumentGrounding(Clock clock) { this.clock = clock; }

    public void validate(Map<String, Object> arguments, Map<String, Object> schema,
                         String originalQuery, Map<Integer, Object> completedOutputs) {
        if (!(schema.get("properties") instanceof Map<?, ?> properties)
            || !(schema.get("required") instanceof List<?> required)) return;
        // Each batch child must meet the same contract as a scalar call.
        if (arguments.get("calls") instanceof List<?> calls) {
            for (Object call : calls) if (call instanceof Map<?, ?> entry
                && entry.get("arguments") instanceof Map<?, ?> child) {
                validate(cast(child), schema, originalQuery, completedOutputs);
            }
            return;
        }
        List<String> missingBasis = new ArrayList<>();
        for (Object key : required) {
            String field = String.valueOf(key);
            if (!(properties.get(field) instanceof Map<?, ?> property)) continue;
            if (!List.of("date", "date-time").contains(property.get("format"))) continue;
            Object value = arguments.get(field);
            if (value == null) continue; // Missing/invalid values are handled by schema validation.
            if (property.containsKey("default") && Objects.equals(value, property.get("default"))) continue;
            String text = String.valueOf(value);
            if (groundedInQuery(text, originalQuery)) continue;
            if (completedOutputs != null && completedOutputs.values().stream()
                .anyMatch(output -> actualRecordContains(output, field, text, 0))) continue;
            missingBasis.add(field);
        }
        if (!missingBasis.isEmpty()) throw new IllegalStateException(
            "USER_INPUT_REQUIRED: required date parameters have no user, declared default, or successful "
                + "record evidence: " + missingBasis + ". Ask the user for the query period before execution; "
                + "do not invent dates or expand the period to an arbitrary full range.");
    }

    private boolean groundedInQuery(String value, String query) {
        if (query == null || query.isBlank()) return false;
        if (query.contains(value)) return true;
        LocalDate date;
        try { date = LocalDate.parse(value.substring(0, 10)); }
        catch (RuntimeException invalidDate) { return false; }
        String localized = date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
        if (query.contains(localized)) return true;
        String year = String.valueOf(date.getYear());
        if (Pattern.compile("(?<![0-9])" + year + "年(?![0-9]+月)").matcher(query).find()
            || Pattern.compile("(?i)\\b(?:in|year|during|for)\\s+" + year + "\\b(?![-/])").matcher(query).find()) return true;
        String month = String.format("%04d-%02d", date.getYear(), date.getMonthValue());
        if (Pattern.compile("(?<![0-9])" + month + "(?![-/0-9])").matcher(query).find()
            || query.contains(date.getYear() + "年" + date.getMonthValue() + "月")) return true;
        LocalDate today = LocalDate.now(clock);
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        if ((query.contains("今年") || lower.contains("this year")) && date.getYear() == today.getYear()) return true;
        if ((query.contains("去年") || lower.contains("last year")) && date.getYear() == today.getYear() - 1) return true;
        if ((query.contains("本月") || lower.contains("this month")) && YearMonth.from(date).equals(YearMonth.from(today))) return true;
        if ((query.contains("上月") || lower.contains("last month")) && YearMonth.from(date).equals(YearMonth.from(today).minusMonths(1))) return true;
        return (query.contains("今天") || lower.contains("today")) && date.equals(today)
            || (query.contains("昨天") || lower.contains("yesterday")) && date.equals(today.minusDays(1));
    }

    private boolean actualRecordContains(Object output, String field, String value, int depth) {
        if (depth > 12 || !(output instanceof Map<?, ?> map)) return false;
        if (Boolean.FALSE.equals(map.get("success")) || "FAILED".equals(map.get("status"))) return false;
        for (String key : List.of("rows", "records")) {
            if (map.get(key) instanceof List<?> records && records.stream().anyMatch(record ->
                record instanceof Map<?, ?> row && value.equals(String.valueOf(row.get(field))))) return true;
        }
        // Only transport/data containers are traversed; schemas and argument echoes are not records.
        for (String key : List.of("data", "structuredContent", "result")) {
            if (actualRecordContains(map.get(key), field, value, depth + 1)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked") private Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
