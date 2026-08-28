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
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern INTERNAL_PAYLOAD_HEADING = Pattern.compile(
        "(?i).*#payload(?:\\s|$).*"
    );

    private UserFacingAnswerSanitizer() {
    }

    public static String sanitize(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }

        String sanitized = removeInternalPayloadSections(answer);
        sanitized = BACKTICK_MAPPING.matcher(sanitized).replaceAll("");
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

    /**
     * Removes raw record-coverage appendices generated for internal analysis datasets.
     * The physical tool response remains available in runtime evidence and audit data.
     */
    private static String removeInternalPayloadSections(String answer) {
        String[] lines = answer.replace("\r", "").split("\n", -1);
        List<String> retained = new ArrayList<>();
        int skippedHeadingLevel = 0;

        for (String line : lines) {
            var heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                if (skippedHeadingLevel > 0 && level <= skippedHeadingLevel) {
                    skippedHeadingLevel = 0;
                }
                if (INTERNAL_PAYLOAD_HEADING.matcher(heading.group(2)).matches()) {
                    skippedHeadingLevel = level;
                    continue;
                }
            }
            if (skippedHeadingLevel == 0) {
                retained.add(line);
            }
        }
        removeEmptyReturnedDataHeadings(retained);
        return String.join("\n", retained);
    }

    private static void removeEmptyReturnedDataHeadings(List<String> lines) {
        for (int index = lines.size() - 1; index >= 0; index--) {
            var heading = MARKDOWN_HEADING.matcher(lines.get(index));
            if (!heading.matches() || heading.group(1).length() != 2
                || !isReturnedDataHeading(heading.group(2))) {
                continue;
            }
            int next = index + 1;
            while (next < lines.size() && lines.get(next).isBlank()) {
                next++;
            }
            if (next == lines.size() || isHeadingAtOrAbove(lines.get(next), 2)) {
                lines.remove(index);
            }
        }
    }

    private static boolean isReturnedDataHeading(String value) {
        String normalized = value == null ? "" : value.replace(" ", "").trim();
        return "已返回数据".equals(normalized) || "returneddata".equalsIgnoreCase(normalized);
    }

    private static boolean isHeadingAtOrAbove(String line, int maximumLevel) {
        var heading = MARKDOWN_HEADING.matcher(line);
        return heading.matches() && heading.group(1).length() <= maximumLevel;
    }
}
