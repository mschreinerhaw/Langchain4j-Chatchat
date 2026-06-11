package com.chatchat.chat.interaction.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves runtime tool policy before the agent loop starts.
 */
@Component
@RequiredArgsConstructor
public class AgentToolPolicyResolver {

    private static final int DEFAULT_MAX_RELEVANT_MCP_TOOLS = 3;
    private static final int MAX_RELEVANT_MCP_TOOLS_LIMIT = 20;
    private static final String DOCUMENT_WORKFLOW_INPUT = "documentWorkflow";
    private static final String DOCUMENT_SEARCH_TOOL = "document_search";

    private static final List<ToolIntentSpec> MCP_TOOL_INTENTS = List.of(
        new ToolIntentSpec("webSearch", "web_search", "web_search", "_web_search")
    );

    private final ToolRegistry toolRegistry;
    private final SkillCatalogService skillCatalogService;
    private final McpToolRegistryBridge mcpToolRegistryBridge;

    public ToolPolicy resolve(InteractionRequest request, SkillDefinition skill) {
        List<String> availableTools = request.getAvailableTools();
        if (availableTools == null || availableTools.isEmpty()) {
            availableTools = discoverDefaultTools(request.getSkillId());
        }
        availableTools = normalizeToolNames(availableTools);

        boolean documentWorkflowRequested = isDocumentWorkflowRequested(request, skill);
        if (documentWorkflowRequested) {
            availableTools = withAvailableTool(availableTools, DOCUMENT_SEARCH_TOOL);
        }

        List<ToolIntentSpec> requestedIntents = resolveRequestedIntents(request);
        List<ToolActivation> activations = new ArrayList<>();
        if (documentWorkflowRequested && isAvailableTool(availableTools, DOCUMENT_SEARCH_TOOL)) {
            activations.add(new ToolActivation(
                DOCUMENT_WORKFLOW_INPUT,
                DOCUMENT_SEARCH_TOOL,
                DOCUMENT_SEARCH_TOOL
            ));
        }
        activations.addAll(resolveToolActivations(requestedIntents, skill));
        List<String> requiredTools = activations.stream()
            .map(ToolActivation::localToolName)
            .distinct()
            .toList();
        availableTools = mergeRequestedTools(availableTools, requiredTools, requestedIntents);
        ToolSelection selection = selectRelevantTools(request, skill, availableTools, requiredTools);
        boolean hasMcpBinding = hasMcpBinding(skill);

        return new ToolPolicy(
            selection.availableTools(),
            requiredTools,
            hasMcpBinding,
            !requiredTools.isEmpty(),
            activations.stream().map(ToolActivation::intentName).toList(),
            selection.selectedCandidateTools(),
            selection.skippedToolReasons()
        );
    }

    private List<String> discoverDefaultTools(String skillId) {
        Map<String, List<String>> mcpToolsByServiceId = new LinkedHashMap<>();
        mcpToolRegistryBridge.listRegisteredTools().forEach(tool ->
            mcpToolsByServiceId.computeIfAbsent(tool.serviceId(), ignored -> new ArrayList<>())
                .add(tool.localToolName())
        );
        return skillCatalogService.resolveTools(skillId, agentVisibleToolNames(), mcpToolsByServiceId);
    }

    private List<String> agentVisibleToolNames() {
        return toolRegistry.getAllToolNames().stream()
            .filter(this::isAgentVisibleTool)
            .sorted()
            .toList();
    }

    private boolean isAgentVisibleTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        return metadata == null || (metadata.isAgentCompatible() && metadata.isUserVisible());
    }

    private boolean isDocumentWorkflowRequested(InteractionRequest request, SkillDefinition skill) {
        return isIntentRequested(request, DOCUMENT_WORKFLOW_INPUT) || hasDocumentScope(skill);
    }

    private boolean hasDocumentScope(SkillDefinition skill) {
        if (skill == null) {
            return false;
        }
        return (skill.boundDocumentIds() != null && skill.boundDocumentIds().stream().anyMatch(this::hasText))
            || (skill.boundDocumentTags() != null && skill.boundDocumentTags().stream().anyMatch(this::hasText));
    }

    private List<String> withAvailableTool(List<String> availableTools, String toolName) {
        List<String> normalized = normalizeToolNames(availableTools);
        if (!isAvailableTool(normalized, toolName) && toolRegistry.hasTool(toolName)) {
            LinkedHashSet<String> tools = new LinkedHashSet<>(normalized);
            tools.add(toolName);
            return new ArrayList<>(tools);
        }
        return normalized;
    }

    private boolean isAvailableTool(List<String> availableTools, String toolName) {
        return availableTools != null && availableTools.contains(toolName);
    }

    private List<ToolIntentSpec> resolveRequestedIntents(InteractionRequest request) {
        return MCP_TOOL_INTENTS.stream()
            .filter(spec -> isIntentRequested(request, spec.inputKey()))
            .toList();
    }

    private List<ToolActivation> resolveToolActivations(List<ToolIntentSpec> requestedIntents, SkillDefinition skill) {
        if (requestedIntents == null || requestedIntents.isEmpty()) {
            return List.of();
        }
        List<ToolActivation> activations = new ArrayList<>();
        for (ToolIntentSpec spec : requestedIntents) {
            resolveMcpTool(spec).stream()
                .filter(localToolName -> isToolEnabledForSkill(skill, localToolName))
                .findFirst()
                .ifPresent(localToolName -> activations.add(new ToolActivation(
                    spec.inputKey(),
                    spec.requestedToolName(),
                    localToolName
                )));
        }
        return activations;
    }

    private List<String> resolveMcpTool(ToolIntentSpec spec) {
        return mcpToolRegistryBridge.listRegisteredTools().stream()
            .filter(tool -> matchesMcpToolIntent(tool, spec))
            .map(McpToolRegistryBridge.RegisteredMcpTool::localToolName)
            .filter(toolRegistry::hasTool)
            .sorted()
            .toList();
    }

    private boolean matchesMcpToolIntent(McpToolRegistryBridge.RegisteredMcpTool tool, ToolIntentSpec spec) {
        if (tool == null || spec == null) {
            return false;
        }
        String remoteToolName = tool.remoteToolName();
        if (remoteToolName != null && spec.remoteToolName().equalsIgnoreCase(remoteToolName.trim())) {
            return true;
        }
        String localToolName = tool.localToolName();
        return localToolName != null
            && localToolName.toLowerCase(Locale.ROOT).endsWith(spec.localToolNameSuffix());
    }

    private boolean isToolEnabledForSkill(SkillDefinition skill, String toolName) {
        if (skill == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        if (skill.boundMcpToolNames() != null && skill.boundMcpToolNames().stream()
            .anyMatch(boundToolName -> toolName.equals(boundToolName))) {
            return true;
        }
        return skill.toolConfigs() != null && skill.toolConfigs().stream()
            .anyMatch(config -> config != null
                && (config.enabled() == null || config.enabled())
                && toolName.equals(config.toolName()));
    }

    private List<String> mergeRequestedTools(List<String> tools,
                                             List<String> requiredTools,
                                             List<ToolIntentSpec> requestedIntents) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (requiredTools != null && !requiredTools.isEmpty()) {
            merged.addAll(requiredTools);
        }
        LinkedHashSet<String> requestedToolNames = new LinkedHashSet<>();
        if (requestedIntents != null) {
            requestedIntents.stream()
                .map(ToolIntentSpec::requestedToolName)
                .forEach(requestedToolNames::add);
        }
        if (tools != null) {
            for (String tool : tools) {
                if (requestedToolNames.contains(tool)) {
                    continue;
                }
                merged.add(tool);
            }
        }
        return new ArrayList<>(merged);
    }

    private boolean isIntentRequested(InteractionRequest request, String inputKey) {
        if (request == null || request.getToolInput() == null) {
            return false;
        }
        Object value = request.getToolInput().get(inputKey);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private ToolSelection selectRelevantTools(InteractionRequest request,
                                              SkillDefinition skill,
                                              List<String> availableTools,
                                              List<String> requiredTools) {
        List<String> normalizedTools = normalizeToolNames(availableTools);
        if (normalizedTools.isEmpty()) {
            return new ToolSelection(List.of(), List.of(), Map.of());
        }

        LinkedHashSet<String> required = new LinkedHashSet<>(normalizeToolNames(requiredTools));
        Set<String> registeredMcpToolNames = new LinkedHashSet<>();
        mcpToolRegistryBridge.listRegisteredTools().forEach(tool -> registeredMcpToolNames.add(tool.localToolName()));

        List<ScoredTool> scoredMcpTools = normalizedTools.stream()
            .filter(toolName -> isMcpToolName(toolName, registeredMcpToolNames))
            .filter(toolName -> !required.contains(toolName))
            .map(toolName -> new ScoredTool(toolName, scoreToolForQuery(toolName, request == null ? null : request.getQuery())))
            .filter(scored -> scored.score() > 0)
            .sorted(Comparator
                .comparingInt(ScoredTool::score)
                .reversed()
                .thenComparing(ScoredTool::toolName))
            .toList();

        boolean hasRelevantMcpSignal = !scoredMcpTools.isEmpty();
        int maxRelevantMcpTools = resolveMaxRelevantMcpTools(skill);
        Set<String> selectedMcpTools = scoredMcpTools.stream()
            .limit(maxRelevantMcpTools)
            .map(ScoredTool::toolName)
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        required.stream()
            .filter(normalizedTools::contains)
            .forEach(ordered::add);
        selectedMcpTools.forEach(ordered::add);

        for (String toolName : normalizedTools) {
            if (ordered.contains(toolName)) {
                continue;
            }
            if (hasRelevantMcpSignal && isMcpToolName(toolName, registeredMcpToolNames)) {
                continue;
            }
            ordered.add(toolName);
        }

        Map<String, String> skipped = new LinkedHashMap<>();
        if (hasRelevantMcpSignal) {
            for (String toolName : normalizedTools) {
                if (isMcpToolName(toolName, registeredMcpToolNames) && !ordered.contains(toolName)) {
                    skipped.put(toolName, "not selected for this query; a more relevant MCP candidate was available");
                }
            }
        }

        List<String> selectedCandidates = new ArrayList<>(required);
        selectedMcpTools.stream()
            .filter(toolName -> !selectedCandidates.contains(toolName))
            .forEach(selectedCandidates::add);
        return new ToolSelection(new ArrayList<>(ordered), selectedCandidates, skipped);
    }

    private int resolveMaxRelevantMcpTools(SkillDefinition skill) {
        if (skill == null || skill.routingSettings() == null || skill.routingSettings().maxRelevantMcpTools() == null) {
            return DEFAULT_MAX_RELEVANT_MCP_TOOLS;
        }
        return Math.max(1, Math.min(MAX_RELEVANT_MCP_TOOLS_LIMIT, skill.routingSettings().maxRelevantMcpTools()));
    }

    private int scoreToolForQuery(String toolName, String query) {
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return 0;
        }
        String searchable = searchableToolText(toolName);
        if (searchable.isBlank()) {
            return 0;
        }
        int score = 0;
        String normalizedQuery = normalizeSearchText(query);
        if (!normalizedQuery.isBlank() && searchable.contains(normalizedQuery)) {
            score += 8;
        }
        for (String term : terms) {
            if (searchable.contains(term)) {
                score += term.length() >= 4 ? 3 : 2;
            }
        }
        return score;
    }

    private String searchableToolText(String toolName) {
        StringBuilder text = new StringBuilder();
        appendSearchText(text, toolName);
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata != null) {
            appendSearchText(text, metadata.getTitle());
            appendSearchText(text, metadata.getDescription());
            appendSearchText(text, metadata.getAuthor());
            appendSearchText(text, metadata.getCategories());
            appendSearchText(text, metadata.getTags());
            if (metadata.getMetadata() != null) {
                appendSearchText(text, metadata.getMetadata().get("remoteToolName"));
                appendSearchText(text, metadata.getMetadata().get("serviceId"));
            }
        }
        return normalizeSearchText(text.toString());
    }

    private void appendSearchText(StringBuilder text, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                appendSearchText(text, item);
            }
            return;
        }
        text.append(' ').append(value);
    }

    private List<String> queryTerms(String query) {
        String normalized = normalizeSearchText(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 2) {
                terms.add(token);
                if (containsCjk(token) && token.length() > 4) {
                    for (int i = 0; i <= token.length() - 2; i++) {
                        terms.add(token.substring(i, i + 2));
                    }
                }
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean containsCjk(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fa5') {
                return true;
            }
        }
        return false;
    }

    private String normalizeSearchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> normalizeToolNames(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
            .filter(tool -> tool != null && !tool.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private boolean isMcpToolName(String toolName, Set<String> registeredMcpToolNames) {
        return toolName != null
            && (toolName.startsWith("mcp_")
            || (registeredMcpToolNames != null && registeredMcpToolNames.contains(toolName)));
    }

    private boolean hasMcpBinding(SkillDefinition skill) {
        if (skill == null) {
            return false;
        }
        if (skill.boundMcpServiceIds() != null && !skill.boundMcpServiceIds().isEmpty()) {
            return true;
        }
        if (skill.boundMcpToolNames() != null && !skill.boundMcpToolNames().isEmpty()) {
            return true;
        }
        return skill.toolConfigs() != null && skill.toolConfigs().stream()
            .anyMatch(config -> config != null
                && (config.enabled() == null || config.enabled())
                && config.toolName() != null
                && !config.toolName().isBlank());
    }

    public record ToolPolicy(
        List<String> availableTools,
        List<String> requiredTools,
        boolean hasMcpBinding,
        boolean requireBoundToolCall,
        List<String> activatedIntents,
        List<String> selectedCandidateTools,
        Map<String, String> skippedToolReasons
    ) {
    }

    private record ToolIntentSpec(
        String inputKey,
        String requestedToolName,
        String remoteToolName,
        String localToolNameSuffix
    ) {
    }

    private record ToolActivation(
        String intentName,
        String requestedToolName,
        String localToolName
    ) {
    }

    private record ScoredTool(
        String toolName,
        int score
    ) {
    }

    private record ToolSelection(
        List<String> availableTools,
        List<String> selectedCandidateTools,
        Map<String, String> skippedToolReasons
    ) {
    }
}
