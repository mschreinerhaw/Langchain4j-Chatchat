package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.protocol.AnswerContract;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the user-facing presentation contract to governed answer content.
 * It owns tables, evidence sections, citations and transport-envelope cleanup,
 * but never selects, reviews or repairs an answer candidate.
 */
final class AnswerUserFacingPolicy {

    private static final int TOOL_DATA_INLINE_CELL_LIMIT = 240;
    private static final int TOOL_DATA_MARKDOWN_ROW_LIMIT = 20;
    private static final Pattern DOCUMENT_REF_PATTERN =
        Pattern.compile("doc://([^\\s\"',;\\]\\)}]+)#chunk=([^\\s\"',;\\]\\)}]+)");
    private static final Pattern WEB_REF_PATTERN =
        Pattern.compile("web://([^\\s\"',;\\]\\)}]+)#result=([^\\s\"',;\\]\\)}]+)");

    private final AgentResultPresentationService resultPresentationService;
    private final ObjectMapper objectMapper;

    AnswerUserFacingPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.resultPresentationService = new AgentResultPresentationService(this.objectMapper);
    }

    Map<String, Object> toolResultVisualizationSpec(List<InteractionToolTrace> traces) {
        return resultPresentationService.toolResultVisualizationSpec(traces);
    }

    boolean supportsStructuredResultPresentation(Map<String, Object> metadata) {
        return resultPresentationService.supportsStructuredResultPresentation(metadata);
    }

    Map<String, Object> parseObject(String text) {
        return resultPresentationService.parseObject(text);
    }

    Map<String, Object> copyMap(Map<?, ?> source) {
        return resultPresentationService.copyMap(source);
    }

    List<Map<String, Object>> toolResultEvidence(List<InteractionToolTrace> traces) {
        return resultPresentationService.toolResultEvidence(traces);
    }

    String appendToolResultTable(String answer, Map<String, Object> visualizationSpec) {
        return resultPresentationService.appendToolResultTable(answer, visualizationSpec);
    }
    String appendToolEvidence(String answer, List<Map<String, Object>> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return answer == null ? "" : answer;
        }
        String base = answer == null ? "" : answer.trim();
        if (base.contains("## 工具执行证据")) {
            return base;
        }
        StringBuilder section = new StringBuilder();
        section.append("## 工具执行证据\n\n");
        section.append("以下为本次工具调用证明，仅展示必要摘要；完整结构化结果保留在运行元数据中。\n\n");
        int index = 1;
        for (Map<String, Object> item : evidence) {
            section.append(index++).append(". `")
                .append(escapeInline(firstNonBlank(stringValue(item.get("toolName")), "unknown_tool")))
                .append("`");
            String displayName = stringValue(item.get("displayName"));
            if (displayName != null && !displayName.isBlank() && !displayName.equals(item.get("toolName"))) {
                section.append("（").append(escapeInline(displayName)).append("）");
            }
            section.append("：").append(Boolean.TRUE.equals(item.get("success")) ? "成功" : "失败")
                .append("，证据类型 `").append(escapeInline(firstNonBlank(stringValue(item.get("evidenceType")), "unknown"))).append("`");
            appendEvidenceField(section, "行数", item.get("rowCount"));
            appendEvidenceField(section, "返回行数", item.get("returnedRowCount"));
            appendEvidenceField(section, "结果集", item.get("resultSetCount"));
            appendEvidenceField(section, "模板", compactListText(item.get("templateIds"), 8));
            appendEvidenceField(section, "退出码", item.get("exitCode"));
            appendEvidenceField(section, "HTTP 状态", item.get("statusCode"));
            appendEvidenceField(section, "耗时 ms", item.get("durationMs"));
            appendEvidenceField(section, "字段", compactListText(item.get("columns"), 8));
            appendEvidenceField(section, "输出键", compactListText(item.get("keys"), 8));
            appendEvidenceField(section, "摘要", evidenceSummary(item));
            section.append("。\n");
        }
        return base.isBlank() ? section.toString().trim() : base + "\n\n" + section.toString().trim();
    }

    String appendFailedToolLimitations(String answer, List<Map<String, Object>> evidence) {
        List<Map<String, Object>> failures = evidence == null ? List.of() : evidence.stream()
            .filter(item -> !Boolean.TRUE.equals(item.get("success")))
            .toList();
        String base = answer == null ? "" : answer.trim();
        if (failures.isEmpty()) {
            return base;
        }
        StringBuilder section = new StringBuilder("## 数据限制\n\n");
        for (Map<String, Object> item : failures) {
            String source = firstNonBlank(stringValue(item.get("displayName")),
                firstNonBlank(stringValue(item.get("toolName")), "数据源"));
            String reason = firstNonBlank(stringValue(item.get("errorMessage")), evidenceSummary(item));
            section.append("- ").append(escapeInline(source)).append("：失败");
            if (!reason.isBlank()) {
                section.append("，").append(escapeInline(reason));
            }
            section.append("。\n");
        }
        String limitation = section.toString().trim();
        if (base.contains(limitation)) {
            return base;
        }
        return base.isBlank() ? limitation : base + "\n\n" + limitation;
    }

    public boolean shouldExposeToolEvidence(String query, Map<String, Object> metadata) {
        if (metadata != null && (Boolean.TRUE.equals(metadata.get("includeToolEvidence"))
            || Boolean.TRUE.equals(metadata.get("showToolEvidence")))) {
            return true;
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        return containsAny(normalized,
            "工具执行证据", "证据链", "调用详情", "执行详情", "审计信息", "审计报告", "数据溯源",
            "tool execution evidence", "evidence chain", "tool calls", "execution details",
            "audit trail", "provenance");
    }

    public boolean shouldExposeEvidenceReferences(String query, Map<String, Object> metadata) {
        if (shouldExposeToolEvidence(query, metadata)) {
            return true;
        }
        if (metadata != null && (Boolean.TRUE.equals(metadata.get("showEvidenceReferences"))
            || Boolean.TRUE.equals(metadata.get("includeCitations"))
            || Boolean.TRUE.equals(metadata.get("answerEvidenceRequired"))
            || AnswerContract.EVIDENCE_REQUIRED.equals(metadata.get("answerEvidencePolicy")))) {
            return true;
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return containsAny(normalized,
            "附上来源", "注明来源", "提供来源", "列出来源", "参考资料", "引用链接", "网页链接",
            "给出出处", "标注出处", "可核验引用", "citation", "cite sources", "source links",
            "provide sources", "references", "provenance", "cited", "citations", "web evidence");
    }

    String applyUserFacingSectionPolicy(String answer,
                                                String query,
                                                Map<String, Object> metadata) {
        String result = answer == null ? "" : answer.trim();
        boolean evidenceRequested = shouldExposeEvidenceReferences(query, metadata);
        if (!evidenceRequested) {
            result = removeMarkdownSections(result,
                "工具执行证据", "证据链", "证据覆盖", "证据覆盖与限制", "证据与来源",
                "证据链与来源", "检索证据", "引用与来源", "来源与引用",
                "Tool Execution Evidence", "Evidence Chain", "Evidence Coverage",
                "Evidence Coverage and Limitations", "Sources and Citations");
            result = removeEvidenceBookkeepingParagraphs(result);
        }
        if (metadata != null && !result.equals(answer == null ? "" : answer.trim())) {
            metadata.put("userFacingSectionPolicyApplied", true);
        }
        return result;
    }

    String applyUserFacingEvidenceReferencePolicy(String answer,
                                                           String query,
                                                           Map<String, Object> metadata) {
        String original = answer == null ? "" : answer.trim();
        if (original.isBlank() || query == null || shouldExposeEvidenceReferences(query, metadata)) {
            return original;
        }
        String result = original
            .replaceAll("(?im)^>\\s*\\*\\*证据完整性提示\\*\\*[:：].*$", "")
            .replaceAll("(?i)\\s*\\[(?:evidence|证据)\\s*:[^]\\r\\n]*]", "")
            .replaceAll("(?i)\\s*\\[(?:网页|web\\s*page)\\s*\\d+]", "")
            .replaceAll("(?i)(?:tool|doc|web)://[^\\s，。；、！？,;]+", "")
            .replaceAll("[ \\t]+([，。；、！？,;])", "$1")
            .replaceAll("(?m)^[ \\t]+$", "")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
        if (metadata != null && !result.equals(original)) {
            metadata.put("userFacingEvidenceReferencesSuppressed", true);
            metadata.put("userFacingEvidenceReferencePolicy", "METADATA_ONLY");
        }
        return result;
    }

    private String removeMarkdownSections(String answer, String... headings) {
        String result = answer;
        for (String heading : headings) {
            result = result.replaceAll("(?ms)^#{1,6}\\s*(?:\\d+(?:\\.\\d+)*[.、]?\\s*)?"
                + java.util.regex.Pattern.quote(heading)
                + "\\s*$.*?(?=^#{1,6}\\s+|\\z)", "");
        }
        return result.trim();
    }

    private String removeEvidenceBookkeepingParagraphs(String answer) {
        if (answer == null || answer.isBlank()) return "";
        List<String> retained = new ArrayList<>();
        for (String paragraph : answer.replace("\r", "").split("\n\s*\n")) {
            String normalized = paragraph.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (!isEvidenceBookkeepingParagraph(normalized)) retained.add(paragraph.trim());
        }
        return String.join("\n\n", retained).trim();
    }

    private boolean isEvidenceBookkeepingParagraph(String value) {
        return containsAny(value,
            "检索返回且被选中的片段", "未选中另一文档", "被证据评估拒绝",
            "本次选定证据仅包含", "文档大纲显示尚有", "未被检索选中",
            "web_search 执行成功", "web_search执行成功", "selected evidence chunks",
            "rejected evidence", "rejected document", "evidence coverage",
            "chunks selected for evidence", "not selected by evidence evaluation");
    }

    private void appendEvidenceField(StringBuilder section, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            section.append("，").append(label).append("=").append(escapeInline(String.valueOf(value)));
        }
    }

    private void appendEvidenceBlock(StringBuilder section, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        section.append("   - ").append(label).append("：").append(escapeInline(value)).append("\n");
    }

    private String listText(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }

    private String compactListText(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        List<String> values = list.stream()
            .limit(Math.max(1, limit))
            .map(String::valueOf)
            .toList();
        String suffix = list.size() > values.size() ? " 等" + list.size() + "项" : "";
        return String.join(", ", values) + suffix;
    }

    private String evidenceSummary(Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return "";
        }
        if (item.get("errorMessage") != null) {
            return shortEvidenceText(stringValue(item.get("errorMessage")), 160);
        }
        String type = stringValue(item.get("evidenceType"));
        if ("tabular".equals(type)) {
            Object rowCount = firstPresent(item.get("rowCount"), item.get("returnedRowCount"));
            return rowCount == null ? "已返回表格数据" : "已返回 " + rowCount + " 行表格数据";
        }
        if ("linux_command".equals(type)) {
            Object stepCount = item.get("stepCount");
            return stepCount == null ? "命令执行完成" : "命令执行完成，步骤数 " + stepCount;
        }
        if ("http_response".equals(type)) {
            return "HTTP 调用完成";
        }
        if ("result_set_batch".equals(type)) {
            if ("FAILED".equalsIgnoreCase(stringValue(item.get("batchStatus")))) {
                return "批处理执行失败，未产生可用结果集";
            }
            return "已按模板返回 " + firstPresent(item.get("resultSetCount"), 0) + " 个独立结果集";
        }
        if ("json".equals(type)) {
            return "已返回结构化 JSON";
        }
        if ("text".equals(type)) {
            return shortEvidenceText(stringValue(item.get("outputPreview")), 160);
        }
        return "";
    }

    private String shortEvidenceText(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        int max = Math.max(20, limit);
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String escapeTableCell(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
    }

    private record LongTextCell(int rowNumber, String column, String value) {
    }

    private String escapeInline(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
    }

    private String previewText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String previewStructured(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return previewText(text);
        }
        try {
            return previewText(objectMapper.writeValueAsString(value));
        } catch (Exception ignored) {
            return previewText(String.valueOf(value));
        }
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private int firstInt(Object first, Object second) {
        Integer firstValue = intValue(first);
        if (firstValue != null) {
            return firstValue;
        }
        Integer secondValue = intValue(second);
        return secondValue == null ? 0 : secondValue;
    }

    private int firstInt(Object first, Object second, Object third, int fallback) {
        for (Object value : new Object[]{first, second, third}) {
            Integer parsed = intValue(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return fallback;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    String citationsText(Object value) {
        if (!(value instanceof List<?> citations) || citations.isEmpty()) {
            return "";
        }
        List<String> refs = new ArrayList<>();
        for (Object item : citations) {
            if (item instanceof Map<?, ?> map) {
                String ref = firstNonBlank(
                    stringValue(map.get("sourceRef")),
                    firstNonBlank(stringValue(map.get("refId")), stringValue(map.get("citation")))
                );
                if (ref != null && !ref.isBlank()) {
                    refs.add(ref);
                }
            } else if (item != null) {
                refs.add(String.valueOf(item));
            }
        }
        return refs.stream()
            .filter(ref -> ref != null && !ref.isBlank())
            .distinct()
            .toList()
            .stream()
            .reduce((left, right) -> left + "；" + right)
            .orElse("");
    }

    boolean looksLikeJson(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    String stripOuterFenceIfPresent(String text, String language) {
        if (text == null || language == null) {
            return text;
        }
        String trimmed = text.trim();
        String openingFence = "```" + language;
        if (trimmed.regionMatches(true, 0, openingFence, 0, openingFence.length())) {
            return stripOuterFence(trimmed, trimmed.substring(0, openingFence.length()));
        }
        return text;
    }

    String stripOuterFence(String text, String openingFence) {
        int start = openingFence.length();
        int end = text.lastIndexOf("```");
        if (end <= start) {
            return text;
        }
        return text.substring(start, end).trim();
    }

    String extractBetween(String text, String beginMarker, String endMarker) {
        if (text == null || beginMarker == null || endMarker == null) {
            return null;
        }
        int begin = text.indexOf(beginMarker);
        int end = text.indexOf(endMarker);
        if (begin < 0 || end <= begin) {
            return null;
        }
        return text.substring(begin + beginMarker.length(), end);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private Object firstPresent(Object first, Object second) {
        return first != null ? first : second;
    }

    private boolean containsAny(String value, String... candidates) {
        if (value == null || candidates == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()
                && normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
