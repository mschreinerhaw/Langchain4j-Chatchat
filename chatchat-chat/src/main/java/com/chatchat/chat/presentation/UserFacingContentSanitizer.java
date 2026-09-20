package com.chatchat.chat.presentation;

import com.chatchat.common.interaction.UserFacingAnswerSanitizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        text = text.replaceAll("(?m)[ \\t]+$", "").trim();
        return UserFacingAnswerSanitizer.sanitize(text);
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
        Object answerBlocks = sanitized.get("answerBlocks");
        if (answerBlocks instanceof List<?> blocks) {
            List<Object> sanitizedBlocks = new ArrayList<>(blocks.size());
            for (Object block : blocks) {
                if (block instanceof String text) {
                    sanitizedBlocks.add(removeInternalEvidenceMarkers(text));
                    continue;
                }
                if (block instanceof Map<?, ?> source) {
                    Map<String, Object> sanitizedBlock = new LinkedHashMap<>();
                    source.forEach((key, value) -> sanitizedBlock.put(String.valueOf(key), value));
                    for (String field : new String[] {"text", "content", "answer"}) {
                        Object value = sanitizedBlock.get(field);
                        if (value instanceof String text) {
                            sanitizedBlock.put(field, removeInternalEvidenceMarkers(text));
                        }
                    }
                    sanitizedBlocks.add(sanitizedBlock);
                    continue;
                }
                sanitizedBlocks.add(block);
            }
            sanitized.put("answerBlocks", sanitizedBlocks);
        }
        return sanitized;
    }
}
