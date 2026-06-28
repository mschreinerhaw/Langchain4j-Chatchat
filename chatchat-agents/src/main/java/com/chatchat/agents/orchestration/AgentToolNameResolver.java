package com.chatchat.agents.orchestration;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves configured tool names, aliases, and semantic tool groups.
 */
class AgentToolNameResolver {

    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String SEARCH_AND_EXTRACT_TOOL = "search_and_extract";
    private final McpToolRouter mcpToolRouter = new McpToolRouter();

    AgentToolNameResolver() {
    }

    String resolveDocumentSearchTool(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        if (tools.contains(DOCUMENT_SEARCH_TOOL)) {
            return DOCUMENT_SEARCH_TOOL;
        }
        return tools.stream()
            .filter(this::isDocumentSearchToolName)
            .findFirst()
            .orElse(null);
    }

    String resolveVerificationWebSearchTool(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        if (tools.contains(WEB_SEARCH_TOOL)) {
            return WEB_SEARCH_TOOL;
        }
        if (tools.contains(SEARCH_AND_EXTRACT_TOOL)) {
            return SEARCH_AND_EXTRACT_TOOL;
        }
        return tools.stream()
            .filter(this::isWebEvidenceToolName)
            .findFirst()
            .orElse(null);
    }

    String normalizeToolName(String toolName, List<String> availableTools) {
        return normalizeToolName(toolName, Map.of(), availableTools);
    }

    String normalizeToolName(String toolName, Map<String, Object> arguments, List<String> availableTools) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String trimmed = toolName.trim();
        if (availableTools == null || availableTools.isEmpty()) {
            String routed = mcpToolRouter.resolveToolName(trimmed, arguments, availableTools);
            return normalizeKnownToolAlias(routed);
        }
        if (availableTools.contains(trimmed)) {
            return trimmed;
        }
        String routed = mcpToolRouter.resolveToolName(trimmed, arguments, availableTools);
        if (routed != null && availableTools.contains(routed)) {
            return routed;
        }
        String aliased = normalizeKnownToolAlias(trimmed);
        if (availableTools.contains(aliased)) {
            return aliased;
        }
        if (DOCUMENT_SEARCH_TOOL.equals(aliased)) {
            return resolveDocumentSearchTool(availableTools);
        }
        if (WEB_SEARCH_TOOL.equals(aliased)) {
            return resolveVerificationWebSearchTool(availableTools);
        }
        return availableTools.stream()
            .filter(available -> sameToolName(available, trimmed))
            .findFirst()
            .orElse(trimmed);
    }

    boolean sameToolName(String first, String second) {
        String left = toolSemanticKey(first);
        String right = toolSemanticKey(second);
        return left != null && left.equals(right);
    }

    boolean isWebSearchToolName(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).contains(WEB_SEARCH_TOOL);
    }

    boolean isWebEvidenceToolName(String toolName) {
        return isWebSearchToolName(toolName) || isSearchAndExtractToolName(toolName);
    }

    boolean isSearchAndExtractToolName(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).contains(SEARCH_AND_EXTRACT_TOOL);
    }

    boolean isDocumentSearchToolName(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).contains(DOCUMENT_SEARCH_TOOL);
    }

    private String normalizeKnownToolAlias(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim();
        String key = normalized.toLowerCase(Locale.ROOT);
        if (DOCUMENT_SEARCH_TOOL.equals(key)) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (WEB_SEARCH_TOOL.equals(key)) {
            return WEB_SEARCH_TOOL;
        }
        if (SEARCH_AND_EXTRACT_TOOL.equals(key)) {
            return SEARCH_AND_EXTRACT_TOOL;
        }
        return normalized;
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(DOCUMENT_SEARCH_TOOL)) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (normalized.contains(SEARCH_AND_EXTRACT_TOOL)) {
            return SEARCH_AND_EXTRACT_TOOL;
        }
        if (normalized.contains(WEB_SEARCH_TOOL)) {
            return WEB_SEARCH_TOOL;
        }
        if ("asset_query".equals(normalized) || "asset_discovery".equals(normalized)) {
            return McpToolRouter.ASSET_DISCOVERY;
        }
        if ("template_query".equals(normalized) || "template_discovery".equals(normalized)) {
            return McpToolRouter.TEMPLATE_DISCOVERY;
        }
        if (mcpToolRouter.isTypedAssetQuery(normalized)) {
            return McpToolRouter.ASSET_DISCOVERY;
        }
        if (mcpToolRouter.isTypedTemplateQuery(normalized)) {
            return McpToolRouter.TEMPLATE_DISCOVERY;
        }
        return normalized;
    }
}
