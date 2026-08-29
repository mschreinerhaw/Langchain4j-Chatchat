package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.routing.McpToolRouter;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.tool.McpToolNamePolicy;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves configured tool names, aliases, and semantic tool groups.
 */
public class AgentToolNameResolver {

    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String WEB_SEARCH_TOOL = "web_search";
    private static final String SEARCH_AND_EXTRACT_TOOL = "search_and_extract";
    private final McpToolRouter mcpToolRouter = new McpToolRouter();
    private final McpCapabilityHierarchy capabilityHierarchy;

    public AgentToolNameResolver() {
        this(McpCapabilityHierarchy.empty());
    }

    public AgentToolNameResolver(McpCapabilityHierarchy capabilityHierarchy) {
        this.capabilityHierarchy = capabilityHierarchy == null
            ? McpCapabilityHierarchy.empty() : capabilityHierarchy;
    }

    public String resolveDocumentSearchTool(List<String> tools) {
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

    public String resolveVerificationWebSearchTool(List<String> tools) {
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

    public String normalizeToolName(String toolName, List<String> availableTools) {
        return normalizeToolName(toolName, Map.of(), availableTools);
    }

    public String normalizeToolName(String toolName, Map<String, Object> arguments, List<String> availableTools) {
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

    public boolean sameToolName(String first, String second) {
        if (capabilityHierarchy.node(first).isPresent() || capabilityHierarchy.node(second).isPresent()) {
            return capabilityHierarchy.sameNode(first, second);
        }
        String left = toolSemanticKey(first);
        String right = toolSemanticKey(second);
        return left != null && left.equals(right);
    }

    public boolean isWebSearchToolName(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).contains(WEB_SEARCH_TOOL);
    }

    public boolean isWebEvidenceToolName(String toolName) {
        return isWebSearchToolName(toolName) || isSearchAndExtractToolName(toolName);
    }

    public boolean isSearchAndExtractToolName(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).contains(SEARCH_AND_EXTRACT_TOOL);
    }

    public boolean isDocumentSearchToolName(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).contains(DOCUMENT_SEARCH_TOOL);
    }

    public boolean isAssetDiscoveryToolName(String toolName) {
        return McpToolRouter.ASSET_DISCOVERY.equals(toolSemanticKey(toolName));
    }

    public boolean isTemplateDiscoveryToolName(String toolName) {
        return McpToolRouter.TEMPLATE_DISCOVERY.equals(toolSemanticKey(toolName));
    }

    /**
     * Resolves an abstract capability request to its unique scoped business
     * implementation. Multiple implementations are intentionally ambiguous.
     */
    public String resolveMostSpecificAvailableTool(String requestedToolName,
                                                   List<String> availableTools) {
        if (requestedToolName == null || requestedToolName.isBlank()
            || availableTools == null || availableTools.isEmpty()) {
            return null;
        }
        List<String> candidates = availableTools.stream()
            .filter(available -> capabilityHierarchy.sameNode(available, requestedToolName)
                || capabilityHierarchy.isImplementationOf(available, requestedToolName))
            .distinct()
            .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> mostSpecific = capabilityHierarchy.mostSpecific(candidates);
        return mostSpecific.size() == 1 && capabilityHierarchy.directlyInvocable(mostSpecific.get(0))
            ? mostSpecific.get(0) : null;
    }

    public boolean isAbstractCapability(String toolName) {
        return capabilityHierarchy.node(toolName)
            .map(node -> node.abstractCapability())
            .orElse(false);
    }

    /**
     * Builds the capability surface exposed to the planner.
     *
     * <p>When an authorized business implementation and its abstract parent are
     * both present, only the implementation is model-visible. The implementation
     * keeps its business/authorization identity while the integration layer still
     * delegates the physical call to the parent bridge. If no implementation is
     * present in the caller's authorized tool set, the parent remains visible as
     * the generic fallback.</p>
     */
    public List<String> plannerVisibleTools(List<String> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return List.of();
        }
        return capabilityHierarchy.mostSpecific(availableTools);
    }

    /**
     * Runtime-only audit of confirmed parent-to-leaf delegation relationships.
     * Keys are deliberately excluded from the planner-visible tool surface.
     */
    public Map<String, List<String>> plannerInternalDelegations(List<String> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return Map.of();
        }
        List<String> visibleTools = plannerVisibleTools(availableTools);
        Map<String, List<String>> delegations = new LinkedHashMap<>();
        for (String candidateParent : availableTools) {
            List<String> implementations = visibleTools.stream()
                .filter(candidate -> capabilityHierarchy.isImplementationOf(
                    candidate, candidateParent))
                .toList();
            if (!implementations.isEmpty()) {
                delegations.put(candidateParent, implementations);
            }
        }
        return java.util.Collections.unmodifiableMap(delegations);
    }

    private String normalizeKnownToolAlias(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim();
        String semantic = toolSemanticKey(normalized);
        if (DOCUMENT_SEARCH_TOOL.equals(semantic)) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (WEB_SEARCH_TOOL.equals(semantic)) {
            return WEB_SEARCH_TOOL;
        }
        if (SEARCH_AND_EXTRACT_TOOL.equals(semantic)) {
            return SEARCH_AND_EXTRACT_TOOL;
        }
        return normalized;
    }

    private String toolSemanticKey(String toolName) {
        String normalized = McpToolNamePolicy.workflowSemanticKey(toolName);
        if (normalized.isBlank()) {
            return null;
        }
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
