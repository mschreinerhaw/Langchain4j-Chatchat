package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Applies default arguments and runtime-bound document filters before tool execution.
 */
class AgentToolArgumentResolver {

    private final AgentToolNameResolver toolNames;
    private final int webSearchReferenceLimit;
    private final ToolRegistry toolRegistry;
    private final McpParamBindingResolver mcpParamBindingResolver = new McpParamBindingResolver();

    AgentToolArgumentResolver(AgentToolNameResolver toolNames, int webSearchReferenceLimit) {
        this(toolNames, webSearchReferenceLimit, null);
    }

    AgentToolArgumentResolver(AgentToolNameResolver toolNames, int webSearchReferenceLimit, ToolRegistry toolRegistry) {
        this.toolNames = toolNames;
        this.webSearchReferenceLimit = webSearchReferenceLimit;
        this.toolRegistry = toolRegistry;
    }

    Map<String, Object> applyDocumentSearchDefaults(String toolName,
                                                    Map<String, Object> arguments,
                                                    List<String> boundDocumentIds,
                                                    List<String> boundDocumentTags) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Collections.emptyMap() : arguments);
        if (!toolNames.isDocumentSearchToolName(toolName)) {
            return values;
        }
        if (!strictDocumentScope(values)) {
            values.remove("document_ids");
            values.remove("documentIds");
            values.remove("fileIds");
            values.remove("file_ids");
            values.remove("selectedDocumentIds");
            values.remove("selected_document_ids");
            values.remove("selectedFileIds");
            values.remove("selected_file_ids");
            values.remove("allowedDocIds");
            values.remove("allowed_doc_ids");
            values.remove("documentVisibilityEnforced");
            values.remove("document_visibility_enforced");
            values.remove("tags");
        } else if (!boundDocumentIds.isEmpty() && !hasAnyKey(values, "document_ids", "documentIds", "fileIds", "file_ids")) {
            values.put("document_ids", boundDocumentIds);
            if (!hasAnyKey(values, "selectedDocumentIds", "selected_document_ids", "selectedFileIds", "selected_file_ids", "allowedDocIds", "allowed_doc_ids")) {
                values.put("selectedDocumentIds", boundDocumentIds);
                values.put("documentVisibilityEnforced", true);
            }
            if (!boundDocumentTags.isEmpty() && !values.containsKey("tags")) {
                values.put("tags", boundDocumentTags);
            }
        }
        return values;
    }

    Map<String, Object> applyToolDefaults(String toolName,
                                          Map<String, Object> arguments,
                                          List<String> boundDocumentIds,
                                          List<String> boundDocumentTags,
                                          String query,
                                          int webSearchResultLimit) {
        Map<String, Object> values = applyDocumentSearchDefaults(toolName, arguments, boundDocumentIds, boundDocumentTags);
        if (toolNames.isDocumentSearchToolName(toolName) && !values.containsKey("query") && query != null && !query.isBlank()) {
            values.put("query", query);
        } else if (toolNames.isDocumentSearchToolName(toolName) && query != null && !query.isBlank()) {
            values.put("query", mergedDocumentQuery(query, Objects.toString(values.get("query"), "")));
        }
        if (isNotificationTool(toolName)) {
            return applyMcpParamBinding(toolName, applyNotificationDefaults(values, query), query);
        }
        if (!toolNames.isWebEvidenceToolName(toolName)) {
            return applyMcpParamBinding(toolName, values, query);
        }
        if (!values.containsKey("query") && query != null && !query.isBlank()) {
            values.put("query", query);
        }
        if (!values.containsKey("num_results")) {
            values.put("num_results", cappedLimit(webSearchResultLimit));
        }
        return applyMcpParamBinding(toolName, values, query);
    }

    Map<String, Object> defaultToolArguments(String toolName, String query, int webSearchResultLimit) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        if ("calculator".equals(toolName)) {
            return Map.of("expression", query);
        }
        if (toolNames.isWebSearchToolName(toolName)) {
            return Map.of("query", query, "num_results", cappedLimit(webSearchResultLimit));
        }
        if (toolNames.isSearchAndExtractToolName(toolName)) {
            return Map.of("query", query, "mode", "fast", "topK", cappedLimit(webSearchResultLimit));
        }
        if (isNotificationTool(toolName)) {
            return applyNotificationDefaults(Map.of(), query);
        }
        if (toolName != null && (toolName.startsWith("mcp_") || toolNames.isDocumentSearchToolName(toolName))) {
            return Map.of("query", query);
        }
        return Map.of("input", query);
    }

    private Map<String, Object> applyNotificationDefaults(Map<String, Object> arguments, String query) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        String content = firstNonBlank(
            stringValue(values.get("content")),
            stringValue(values.get("message")),
            stringValue(values.get("text")),
            stringValue(values.get("query")),
            query
        );
        if (!hasText(values.get("title"))) {
            values.put("title", "Agent 告警通知");
        }
        if (!hasText(values.get("content")) && content != null) {
            values.put("content", content);
        }
        if (!hasText(values.get("level"))) {
            values.put("level", inferNotificationLevel(content));
        }
        values.remove("query");
        return values;
    }

    private Map<String, Object> applyMcpParamBinding(String toolName, Map<String, Object> arguments, String query) {
        return mcpParamBindingResolver.resolve(
            toolName,
            toolRegistry == null ? null : toolRegistry.getToolMetadata(toolName),
            arguments,
            query
        );
    }

    private boolean isNotificationTool(String toolName) {
        if (toolName == null || toolName.isBlank() || toolRegistry == null) {
            return false;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata == null) {
            return false;
        }
        if ("notification".equalsIgnoreCase(firstNonBlank(metadata.getCategory(), ""))) {
            return true;
        }
        if ("notify".equalsIgnoreCase(firstNonBlank(metadata.getOperationType(), ""))) {
            return true;
        }
        if (metadata.getCategories() != null && metadata.getCategories().stream()
            .filter(Objects::nonNull)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains("notification"))) {
            return true;
        }
        Object marker = metadata.getMetadata() == null ? null : metadata.getMetadata().get("notificationTool");
        return marker instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(marker));
    }

    private String inferNotificationLevel(String content) {
        String text = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (text.contains("critical") || text.contains("严重") || text.contains("紧急")) {
            return "CRITICAL";
        }
        if (text.contains("warning") || text.contains("告警") || text.contains("异常")
            || text.contains("失败") || text.contains("风险")) {
            return "WARNING";
        }
        return "INFO";
    }

    private int cappedLimit(int webSearchResultLimit) {
        return Math.max(1, Math.min(webSearchReferenceLimit, webSearchResultLimit));
    }

    private boolean strictDocumentScope(Map<String, Object> values) {
        Object strict = firstPresent(values, "strict_document_scope", "strictDocumentScope");
        if (strict instanceof Boolean flag) {
            return flag;
        }
        Object scopeMode = firstPresent(values, "scope_mode", "scopeMode");
        return scopeMode != null && "strict".equalsIgnoreCase(String.valueOf(scopeMode).trim());
    }

    private boolean hasAnyKey(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String mergedDocumentQuery(String originalQuery, String plannedQuery) {
        String original = originalQuery == null ? "" : originalQuery.trim();
        String planned = plannedQuery == null ? "" : plannedQuery.trim();
        if (original.isBlank()) {
            return planned;
        }
        if (planned.isBlank()) {
            return original;
        }
        if (compact(planned).contains(compact(original))) {
            return planned;
        }
        if (compact(original).contains(compact(planned))) {
            return original;
        }
        return original + " " + planned;
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }
}
