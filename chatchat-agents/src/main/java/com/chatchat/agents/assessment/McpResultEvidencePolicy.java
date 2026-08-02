package com.chatchat.agents.assessment;

import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hard Runtime policy that separates result availability from result completeness.
 */
public final class McpResultEvidencePolicy {

    public static final String CONTRACT_VERSION = "mcp_result_evidence_policy_v1";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RESULT_KEYS = Set.of(
        "data", "result", "results", "rows", "records", "items", "content",
        "payload", "structuredcontent", "output", "value", "values", "resultpresent"
    );
    private static final Set<String> CONTROL_KEYS = Set.of(
        "schemaversion", "success", "status", "message", "error", "errormessage",
        "query", "requestid", "traceid", "durationms", "backend", "retrievalmode",
        "expandedquery", "semanticquery", "detectedscenarios", "limit", "offset",
        "rowcount", "total", "count", "columns", "metadata", "statuscode", "empty_result"
    );

    public Assessment assess(List<InteractionToolTrace> traces) {
        List<InteractionToolTrace> safeTraces = traces == null ? List.of() : traces;
        int successful = 0;
        int available = 0;
        int empty = 0;
        int unavailable = 0;
        int failed = 0;
        for (InteractionToolTrace trace : safeTraces) {
            if (trace == null) {
                continue;
            }
            if (!trace.isSuccess()) {
                failed++;
                unavailable++;
                continue;
            }
            successful++;
            switch (classify(trace.getOutput())) {
                case AVAILABLE -> available++;
                case EMPTY -> empty++;
                case UNAVAILABLE -> unavailable++;
                case PARTIAL -> available++;
            }
        }
        boolean mixed = available > 0 && (empty > 0 || unavailable > 0)
            || empty > 0 && unavailable > 0;
        Availability availability = mixed
            ? Availability.PARTIAL
            : available > 0
                ? Availability.AVAILABLE
                : empty > 0 ? Availability.EMPTY : Availability.UNAVAILABLE;
        return new Assessment(
            CONTRACT_VERSION, availability, safeTraces.size(), successful, failed,
            available, empty, unavailable);
    }

    boolean hasQueryResult(String output) {
        return classify(output) == Availability.AVAILABLE;
    }

    private Availability classify(String output) {
        if (output == null || output.isBlank()) {
            return Availability.EMPTY;
        }
        String text = output.trim();
        if (isFailureText(text)) {
            return Availability.UNAVAILABLE;
        }
        Availability legacyCollection = classifyLegacyJavaCollectionText(text);
        if (legacyCollection != null) {
            return legacyCollection;
        }
        if (looksLikeStructuredPayload(text)) {
            try {
                JsonNode parsed = JSON.readTree(text);
                if (explicitFailure(parsed)) {
                    return Availability.UNAVAILABLE;
                }
                return substantive(parsed, null) ? Availability.AVAILABLE : Availability.EMPTY;
            } catch (Exception ignored) {
                return Availability.UNAVAILABLE;
            }
        }
        return isEmptyText(text) ? Availability.EMPTY : Availability.AVAILABLE;
    }

    /**
     * Older traces may contain Java collection rendering (for example
     * {@code {results=[{title=...}]}}) because the bounded log preview used to
     * degrade a structured map to {@code Map.toString()}. This compatibility
     * path is schema-key driven and does not depend on any tool or template name.
     */
    private Availability classifyLegacyJavaCollectionText(String text) {
        if (text == null || !text.startsWith("{") || !text.contains("=")) {
            return null;
        }
        boolean resultFieldPresent = false;
        for (String key : RESULT_KEYS) {
            Pattern field = Pattern.compile(
                "(?i)(?:^|[\\s,{])" + Pattern.quote(key) + "\\s*=\\s*\\[");
            Matcher matcher = field.matcher(text);
            while (matcher.find()) {
                resultFieldPresent = true;
                int contentStart = matcher.end();
                int cursor = contentStart;
                while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
                    cursor++;
                }
                if (cursor >= text.length() || text.charAt(cursor) != ']') {
                    return Availability.AVAILABLE;
                }
            }
        }
        return resultFieldPresent ? Availability.EMPTY : null;
    }

    private boolean explicitFailure(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode success = node.get("success");
        if (success != null && success.isBoolean() && !success.asBoolean()) {
            return true;
        }
        JsonNode status = node.get("status");
        if (status != null && status.isTextual()) {
            String value = status.asText().trim().toUpperCase(Locale.ROOT);
            if ("FAILED".equals(value) || "FAILURE".equals(value) || "ERROR".equals(value)
                || "TIMEOUT".equals(value) || "UNAVAILABLE".equals(value)) {
                return true;
            }
        }
        JsonNode error = findIgnoreCase(node, "error");
        return error != null && !error.isNull()
            && (!(error.isTextual()) || !error.asText().isBlank());
    }

    private boolean looksLikeStructuredPayload(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.stripLeading();
        return text.startsWith("{") || text.startsWith("[") || text.startsWith("<");
    }

    private boolean isFailureText(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("error:")
            || normalized.startsWith("exception:")
            || normalized.startsWith("timeout")
            || normalized.startsWith("failed")
            || normalized.startsWith("service unavailable")
            || normalized.startsWith("bad gateway")
            || (normalized.startsWith("<html")
                && (normalized.contains("error") || normalized.contains("502") || normalized.contains("503")));
    }

    private boolean substantive(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isTextual()) {
            return !isEmptyText(node.asText());
        }
        if (node.isNumber() || node.isBoolean()) {
            return true;
        }
        if (node.isArray()) {
            if (node.size() == 0) {
                return false;
            }
            for (JsonNode item : node) {
                if (substantive(item, fieldName)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject()) {
            return false;
        }
        JsonNode success = node.get("success");
        if (success != null && success.isBoolean() && !success.asBoolean()) {
            return false;
        }
        JsonNode status = node.get("status");
        if (status != null && status.isTextual() && isEmptyStatus(status.asText())) {
            return false;
        }
        boolean resultFieldPresent = false;
        for (String resultKey : RESULT_KEYS) {
            JsonNode result = findIgnoreCase(node, resultKey);
            if (result != null) {
                resultFieldPresent = true;
                if (substantive(result, resultKey)) {
                    return true;
                }
            }
        }
        if (resultFieldPresent) {
            return false;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (CONTROL_KEYS.contains(key) || RESULT_KEYS.contains(key)) {
                continue;
            }
            if (substantive(entry.getValue(), key)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode findIgnoreCase(JsonNode node, String key) {
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isEmptyText(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
            || "null".equals(normalized)
            || "{}".equals(normalized)
            || "[]".equals(normalized)
            || "no data".equals(normalized)
            || "no result".equals(normalized)
            || "no results".equals(normalized)
            || normalized.contains("empty_result")
            || normalized.contains("未查询到数据")
            || normalized.contains("未返回数据")
            || normalized.contains("无查询结果");
    }

    private boolean isEmptyStatus(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "EMPTY".equals(normalized)
            || "EMPTY_RESULT".equals(normalized)
            || "NO_DATA".equals(normalized)
            || "NO_RESULT".equals(normalized);
    }

    public enum Availability {
        AVAILABLE,
        PARTIAL,
        EMPTY,
        UNAVAILABLE
    }

    public record Assessment(
        String contractVersion,
        Availability availability,
        int totalToolCount,
        int successfulToolCount,
        int failedToolCount,
        int availableResultCount,
        int emptyResultCount,
        int unavailableResultCount
    ) {
        public Assessment {
            contractVersion = CONTRACT_VERSION;
            availability = availability == null ? Availability.UNAVAILABLE : availability;
            totalToolCount = Math.max(0, totalToolCount);
            successfulToolCount = Math.max(0, successfulToolCount);
            failedToolCount = Math.max(0, failedToolCount);
            availableResultCount = Math.max(0, availableResultCount);
            emptyResultCount = Math.max(0, emptyResultCount);
            unavailableResultCount = Math.max(0, unavailableResultCount);
        }

        public boolean resultAvailable() {
            return availableResultCount > 0;
        }

        public boolean partial() {
            return availability == Availability.PARTIAL;
        }
    }
}
