package com.chatchat.common.runtime.analysis.execution;

import com.chatchat.common.tool.ToolOutput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Execution facts only; no assessment of business evidence or report quality. */
public final class RuntimeToolReceipt {
    private RuntimeToolReceipt() {}

    public static Map<String, Object> from(ToolOutput output, String outcome) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", "runtime_tool_receipt.v1");
        receipt.put("outcome", outcome == null ? "UNKNOWN" : outcome);
        String state = outcome == null ? "" : outcome.toUpperCase(Locale.ROOT);
        String status = output == null ? "OUTCOME_UNKNOWN" : !output.isSuccess()
            ? state.contains("DENIED") || state.contains("BLOCKED") ? "DENIED"
                : state.contains("TIMEOUT") ? "TIMEOUT" : "FAILED"
            : empty(output.getData()) ? "EMPTY" : "SUCCESS";
        receipt.put("status", status);
        if (output != null) {
            if (output.getErrorMessage() != null) receipt.put("errorMessage", safeError(output.getErrorMessage()));
            Map<String, Object> metadata = output.getMetadata() == null ? Map.of() : output.getMetadata();
            // Retry permission is an executor fact. Never guess it from error prose.
            for (String key : List.of("errorCode", "retryable", "complete", "truncated", "hasMore", "nextPageToken"))
                if (metadata.get(key) != null) receipt.put(key, metadata.get(key));
        }
        return Map.copyOf(receipt);
    }

    public static String safeError(String text) {
        return text.replaceAll("(?i)(password|passwd|secret|token|authorization)(\\s*[=:]\\s*)[^\\s,;]+", "$1$2[REDACTED]");
    }

    private static boolean empty(Object data) {
        if (data == null) return true;
        if (data instanceof List<?> rows) return rows.isEmpty();
        if (data instanceof Map<?, ?> map) {
            if (map.isEmpty()) return true;
            if (map.get("rows") instanceof List<?> rows) return rows.isEmpty();
        }
        return false;
    }
}
