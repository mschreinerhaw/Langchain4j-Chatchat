package com.chatchat.chat.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Removes internal runtime protocols from content returned to end users. */
public final class UserFacingContentSanitizer {

    private static final Pattern INTERNAL_EVIDENCE_MARKER_PATTERN = Pattern.compile(
        "\\[\\s*evidence\\s*:[^\\]]*]", Pattern.CASE_INSENSITIVE);

    private UserFacingContentSanitizer() {
    }

    public static String removeInternalEvidenceMarkers(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = INTERNAL_EVIDENCE_MARKER_PATTERN.matcher(value).replaceAll("");
        text = text.replaceAll("[ \\t]+([,.;:!?，。；：！？])", "$1");
        return text.replaceAll("(?m)[ \\t]+$", "").trim();
    }

    public static Map<String, Object> sanitizeUiResponse(Map<String, Object> uiResponse) {
        if (uiResponse == null || uiResponse.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(uiResponse);
        for (String field : new String[] {"answer", "reportHtml", "answerHtml", "htmlContent"}) {
            Object value = sanitized.get(field);
            if (value instanceof String text) {
                sanitized.put(field, removeInternalEvidenceMarkers(text));
            }
        }
        return sanitized;
    }
}
