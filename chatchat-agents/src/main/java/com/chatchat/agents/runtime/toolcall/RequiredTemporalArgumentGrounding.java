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
    private static final Pattern RELATIVE_TIME = Pattern.compile(
        "今天|昨天|前天|明天|本周|这周|上周|下周|本月|这个月|上月|下月|本季度|上季度|下季度|今年|去年|明年|(?:最近|过去|近)[0-9一二三四五六七八九十两]+(?:天|周|个月|月|年)"
            + "|(?i:\\b(?:today|yesterday|tomorrow|(?:this|last|next)\\s+(?:week|month|quarter|year)|(?:past|last|next)\\s+\\d+\\s+(?:days?|weeks?|months?|quarters?|years?))\\b)");
    private static final Pattern EXPLICIT_DATE = Pattern.compile(
        "(?<![0-9])(?:[0-9]{4}[-/.][0-9]{1,2}(?:[-/.][0-9]{1,2})?|(?:[0-9]{4}年)?[0-9]{1,2}月(?:[0-9]{1,2}[日号])?|[0-9]{4}年)(?![0-9])");
    private final Clock clock;

    public RequiredTemporalArgumentGrounding() { this(Clock.systemDefaultZone()); }
    public RequiredTemporalArgumentGrounding(Clock clock) { this.clock = clock; }

    public static boolean hasRelativeTimeExpression(String text) {
        return text != null && RELATIVE_TIME.matcher(text).find();
    }

    static boolean hasTemporalEvidence(String text, Object value) {
        if (text == null) return false;
        if (hasRelativeTimeExpression(text) || EXPLICIT_DATE.matcher(text).find()) return true;
        String date = String.valueOf(value);
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T.*)?")) return false;
        if (new RequiredTemporalArgumentGrounding().groundedInQuery(date, text)) return true;
        String compactDate = date.substring(0, 10).replace("-", "");
        return Pattern.compile("(?<![0-9])" + compactDate + "(?![0-9])").matcher(text).find();
    }

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
            if (!"date".equals(property.get("format")) && !"date-time".equals(property.get("format"))) continue;
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
