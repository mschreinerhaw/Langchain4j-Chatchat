package com.chatchat.common.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes runtime reconciliation references from text returned to end users.
 *
 * <p>The evidence ledger and tool traces retain these identifiers separately. They are implementation
 * details rather than useful citations, so they must not leak through an answer or conversation memory.</p>
 */
public final class UserFacingAnswerSanitizer {

    private static final String RECONCILIATION_ALIAS = "(?:SQL|META|STEP)-\\d+";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String MCP_CHUNK_REFERENCE = UUID + ":" + UUID
        + ":[a-z0-9_.:-]+#chunk-\\d+";
    private static final String STEP_REFERENCE = "iteration:\\d+:step:\\d+:tool:[a-z0-9_.:-]+";
    private static final String INTERNAL_REFERENCE = "(?:" + MCP_CHUNK_REFERENCE + "|" + STEP_REFERENCE + ")";

    private static final Pattern BACKTICK_MAPPING = Pattern.compile(
        "(?i)(?<![a-z0-9_])" + RECONCILIATION_ALIAS
            + "[ \\t]*=[ \\t]*`[^`\\r\\n]*`[ \\t]*\\\\?");
    private static final Pattern INTERNAL_MAPPING = Pattern.compile(
        "(?i)(?<![a-z0-9_])" + RECONCILIATION_ALIAS + "[ \\t]*=[ \\t]*`?" + INTERNAL_REFERENCE
            + "`?[ \\t]*\\\\?");
    private static final Pattern INTERNAL_REFERENCE_PATTERN = Pattern.compile(
        "(?i)(?<![a-z0-9_-])" + INTERNAL_REFERENCE + "(?![a-z0-9_-])");
    private static final Pattern INLINE_ALIAS = Pattern.compile(
        "(?i)[ \\t]*\\[[ \\t]*" + RECONCILIATION_ALIAS + "[ \\t]*][ \\t]*");
    private static final Pattern INDEX_ONLY_LINE = Pattern.compile(
        "(?i)^\\s*(?:#{1,6}\\s*)?(?:\\*\\*)?(?:证据索引|evidence\\s+index|reconciliation\\s+index)"
            + "(?:\\*\\*)?(?:\\s|\\\\|[,，;；、:：-])*$");
    private static final Pattern EMPTY_LIST_LINE = Pattern.compile("^\\s*(?:[-*+]\\s*)?$|^\\s*\\d+[.)、]\\s*$");

    private UserFacingAnswerSanitizer() {
    }

    public static String sanitize(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }

        String sanitized = BACKTICK_MAPPING.matcher(answer).replaceAll("");
        sanitized = INTERNAL_MAPPING.matcher(sanitized).replaceAll("");
        sanitized = INTERNAL_REFERENCE_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = INLINE_ALIAS.matcher(sanitized).replaceAll(" ");

        List<String> retainedLines = new ArrayList<>();
        for (String line : sanitized.replace("\r", "").split("\n", -1)) {
            String cleaned = line
                .replaceAll("[ \\t]+([，。；、！？,.;!?])", "$1")
                .replaceAll("[ \\t]+$", "");
            if (INDEX_ONLY_LINE.matcher(cleaned).matches()
                || (EMPTY_LIST_LINE.matcher(cleaned).matches() && !line.isBlank())) {
                continue;
            }
            retainedLines.add(cleaned);
        }
        return String.join("\n", retainedLines)
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
