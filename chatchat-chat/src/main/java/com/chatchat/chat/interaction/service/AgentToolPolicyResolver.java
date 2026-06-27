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

    /**
     * Resolves the resolve.
     *
     * @param request the request value
     * @param skill the skill value
     * @return the resolved resolve
     */
    public ToolPolicy resolve(InteractionRequest request, SkillDefinition skill) {
        List<String> availableTools = request.getAvailableTools();
        if (availableTools == null || availableTools.isEmpty()) {
            availableTools = discoverDefaultTools(request.getSkillId());
        }
        availableTools = normalizeToolNames(availableTools);
        availableTools = applyExecutionContextRouting(request, availableTools);

        WorkflowToolResolution workflowTools = resolveRequiredWorkflowTools(skill, availableTools);
        availableTools = mergeToolNames(availableTools, workflowTools.autoAddedTools());

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
        List<String> activatedRequiredTools = activations.stream()
            .map(ToolActivation::localToolName)
            .distinct()
            .toList();
        List<String> requiredTools = mergeToolNames(workflowTools.requiredTools(), activatedRequiredTools);
        availableTools = mergeRequestedTools(availableTools, requiredTools, requestedIntents);
        ToolSelection selection = selectRelevantTools(request, skill, availableTools, requiredTools);
        boolean hasMcpBinding = hasMcpBinding(skill);
        Map<String, String> skippedToolReasons = new LinkedHashMap<>(workflowTools.skippedToolReasons());
        skippedToolReasons.putAll(selection.skippedToolReasons());

        return new ToolPolicy(
            selection.availableTools(),
            requiredTools,
            hasMcpBinding,
            !requiredTools.isEmpty(),
            activations.stream().map(ToolActivation::intentName).toList(),
            selection.selectedCandidateTools(),
            skippedToolReasons,
            workflowTools.autoAddedTools()
        );
    }

    /**
     * Performs the discover default tools operation.
     *
     * @param skillId the skill id value
     * @return the operation result
     */
    private List<String> discoverDefaultTools(String skillId) {
        Map<String, List<String>> mcpToolsByServiceId = new LinkedHashMap<>();
        mcpToolRegistryBridge.listRegisteredTools().forEach(tool ->
            mcpToolsByServiceId.computeIfAbsent(tool.serviceId(), ignored -> new ArrayList<>())
                .add(tool.localToolName())
        );
        return skillCatalogService.resolveTools(skillId, agentVisibleToolNames(), mcpToolsByServiceId);
    }

    private List<String> applyExecutionContextRouting(InteractionRequest request, List<String> availableTools) {
        if (!hasExecutionContext(request)) {
            return availableTools;
        }
        List<McpToolRegistryBridge.RegisteredMcpTool> registeredTools = mcpToolRegistryBridge.listRegisteredTools();
        if (registeredTools.isEmpty()) {
            return availableTools;
        }
        Map<String, McpToolRegistryBridge.RegisteredMcpTool> toolsByLocalName = new LinkedHashMap<>();
        for (McpToolRegistryBridge.RegisteredMcpTool tool : registeredTools) {
            toolsByLocalName.put(tool.localToolName(), tool);
        }
        String linuxGateway = registeredTools.stream()
            .filter(tool -> "linux_command_execute".equals(tool.remoteToolName()))
            .map(McpToolRegistryBridge.RegisteredMcpTool::localToolName)
            .filter(toolRegistry::hasTool)
            .findFirst()
            .orElse(null);
        String sqlGateway = registeredTools.stream()
            .filter(tool -> "sql_query_execute".equals(tool.remoteToolName()))
            .map(McpToolRegistryBridge.RegisteredMcpTool::localToolName)
            .filter(toolRegistry::hasTool)
            .findFirst()
            .orElse(null);
        String httpGateway = registeredTools.stream()
            .filter(tool -> "http_request_execute".equals(tool.remoteToolName()))
            .map(McpToolRegistryBridge.RegisteredMcpTool::localToolName)
            .filter(toolRegistry::hasTool)
            .findFirst()
            .orElse(null);
        if (linuxGateway == null && sqlGateway == null && httpGateway == null) {
            return availableTools;
        }

        LinkedHashSet<String> routed = new LinkedHashSet<>();
        boolean removedLinuxAsset = false;
        boolean removedSqlAsset = false;
        boolean removedHttpAsset = false;
        for (String toolName : normalizeToolNames(availableTools)) {
            McpToolRegistryBridge.RegisteredMcpTool registered = toolsByLocalName.get(toolName);
            String remoteToolName = registered == null ? "" : normalizeToolName(registered.remoteToolName());
            if (linuxGateway != null && remoteToolName.startsWith("ssh_")) {
                removedLinuxAsset = true;
                continue;
            }
            if (sqlGateway != null && remoteToolName.startsWith("db_query_")) {
                removedSqlAsset = true;
                continue;
            }
            if (httpGateway != null && remoteToolName.startsWith("http_")
                && !"http_request_execute".equals(remoteToolName)) {
                removedHttpAsset = true;
                continue;
            }
            routed.add(toolName);
        }
        if (removedLinuxAsset && linuxGateway != null) {
            routed.add(linuxGateway);
        }
        if (removedSqlAsset && sqlGateway != null) {
            routed.add(sqlGateway);
        }
        if (removedHttpAsset && httpGateway != null) {
            routed.add(httpGateway);
        }
        return new ArrayList<>(routed);
    }

    private boolean hasExecutionContext(InteractionRequest request) {
        if (request == null || request.getToolInput() == null) {
            return false;
        }
        Object context = request.getToolInput().get("mcpExecutionContext");
        if (!(context instanceof Map<?, ?>)) {
            context = request.getToolInput().get("executionContext");
        }
        return context instanceof Map<?, ?> map && !map.isEmpty();
    }

    /**
     * Performs the agent visible tool names operation.
     *
     * @return the operation result
     */
    private List<String> agentVisibleToolNames() {
        return toolRegistry.getAllToolNames().stream()
            .filter(this::isAgentVisibleTool)
            .sorted()
            .toList();
    }

    /**
     * Returns whether is agent visible tool.
     *
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean isAgentVisibleTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        return metadata == null || (metadata.isAgentCompatible() && metadata.isUserVisible());
    }

    /**
     * Returns whether is document workflow requested.
     *
     * @param request the request value
     * @param skill the skill value
     * @return whether the condition is satisfied
     */
    private boolean isDocumentWorkflowRequested(InteractionRequest request, SkillDefinition skill) {
        return isIntentRequested(request, DOCUMENT_WORKFLOW_INPUT) || hasDocumentScope(skill);
    }

    /**
     * Returns whether has document scope.
     *
     * @param skill the skill value
     * @return whether the condition is satisfied
     */
    private boolean hasDocumentScope(SkillDefinition skill) {
        if (skill == null) {
            return false;
        }
        return (skill.boundDocumentIds() != null && skill.boundDocumentIds().stream().anyMatch(this::hasText))
            || (skill.boundDocumentTags() != null && skill.boundDocumentTags().stream().anyMatch(this::hasText));
    }

    /**
     * Performs the with available tool operation.
     *
     * @param availableTools the available tools value
     * @param toolName the tool name value
     * @return the operation result
     */
    private List<String> withAvailableTool(List<String> availableTools, String toolName) {
        List<String> normalized = normalizeToolNames(availableTools);
        if (!isAvailableTool(normalized, toolName) && toolRegistry.hasTool(toolName)) {
            LinkedHashSet<String> tools = new LinkedHashSet<>(normalized);
            tools.add(toolName);
            return new ArrayList<>(tools);
        }
        return normalized;
    }

    private WorkflowToolResolution resolveRequiredWorkflowTools(SkillDefinition skill, List<String> availableTools) {
        List<Map<String, Object>> steps = workflowSteps(skill);
        if (steps.isEmpty()) {
            return new WorkflowToolResolution(List.of(), List.of(), Map.of());
        }
        List<McpToolRegistryBridge.RegisteredMcpTool> registeredTools = mcpToolRegistryBridge.listRegisteredTools();
        LinkedHashSet<String> requiredTools = new LinkedHashSet<>();
        LinkedHashSet<String> autoAddedTools = new LinkedHashSet<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<String> normalizedAvailableTools = normalizeToolNames(availableTools);

        for (Map<String, Object> step : steps) {
            if (step == null || !workflowStepRequired(step)) {
                continue;
            }
            String configuredTool = firstText(step.get("tool"), step.get("toolName"));
            if (configuredTool == null || configuredTool.isBlank()) {
                continue;
            }
            String localTool = resolveWorkflowLocalTool(configuredTool, registeredTools);
            if (localTool == null) {
                skipped.put(configuredTool, "required workflow tool is not registered in MCP registry");
                continue;
            }
            requiredTools.add(localTool);
            if (!normalizedAvailableTools.contains(localTool)) {
                autoAddedTools.add(localTool);
                normalizedAvailableTools = mergeToolNames(normalizedAvailableTools, List.of(localTool));
            }
        }
        return new WorkflowToolResolution(
            new ArrayList<>(requiredTools),
            new ArrayList<>(autoAddedTools),
            skipped
        );
    }

    private List<Map<String, Object>> workflowSteps(SkillDefinition skill) {
        if (skill == null || skill.workflowConfig() == null || skill.workflowConfig().isEmpty()) {
            return List.of();
        }
        Object workflow = skill.workflowConfig().get("mcpWorkflow");
        if (workflow == null) {
            workflow = skill.workflowConfig().get("steps");
        }
        if (workflow == null) {
            workflow = skill.workflowConfig();
        }
        if (workflow instanceof List<?> list) {
            return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> mapOf(item))
                .toList();
        }
        if (workflow instanceof Map<?, ?> map) {
            Object nested = map.get("mcpWorkflow");
            if (nested == null) {
                nested = map.get("steps");
            }
            if (nested instanceof List<?> list) {
                return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> mapOf(item))
                    .toList();
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    private boolean workflowStepRequired(Map<String, Object> step) {
        Object required = step.get("required");
        if (required == null) {
            return false;
        }
        if (required instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(required));
    }

    private String resolveWorkflowLocalTool(String configuredTool,
                                            List<McpToolRegistryBridge.RegisteredMcpTool> registeredTools) {
        String normalized = normalizeToolName(configuredTool);
        if (normalized.isBlank()) {
            return null;
        }
        if (toolRegistry.hasTool(configuredTool)) {
            return configuredTool.trim();
        }
        if (registeredTools == null || registeredTools.isEmpty()) {
            return null;
        }
        for (McpToolRegistryBridge.RegisteredMcpTool registeredTool : registeredTools) {
            if (registeredTool == null || !toolRegistry.hasTool(registeredTool.localToolName())) {
                continue;
            }
            if (normalized.equals(normalizeToolName(registeredTool.localToolName()))
                || normalized.equals(normalizeToolName(registeredTool.remoteToolName()))) {
                return registeredTool.localToolName();
            }
        }
        List<String> aliases = workflowToolAliases(normalized);
        for (McpToolRegistryBridge.RegisteredMcpTool registeredTool : registeredTools) {
            if (registeredTool == null || !toolRegistry.hasTool(registeredTool.localToolName())) {
                continue;
            }
            String remote = normalizeToolName(registeredTool.remoteToolName());
            String local = normalizeToolName(registeredTool.localToolName());
            for (String alias : aliases) {
                if (alias.equals(remote) || local.endsWith("_" + alias)) {
                    return registeredTool.localToolName();
                }
            }
        }
        return null;
    }

    private List<String> workflowToolAliases(String normalizedToolName) {
        return switch (normalizedToolName) {
            case "asset_query", "asset_discovery" -> List.of("asset_query");
            case "template_query", "template_retrieval" -> List.of("template_query");
            case "database_query", "database_diagnosis", "sql_query" ->
                List.of("database_query", "sql_query_execute");
            case "database_execute", "sql_execute", "database_change" ->
                List.of("database_execute", "sql_execute", "sql_write_execute");
            case "host_diagnosis", "linux_command", "linux_command_execute" ->
                List.of("linux_command_execute");
            default -> List.of(normalizedToolName);
        };
    }

    /**
     * Returns whether is available tool.
     *
     * @param availableTools the available tools value
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean isAvailableTool(List<String> availableTools, String toolName) {
        return availableTools != null && availableTools.contains(toolName);
    }

    /**
     * Resolves the requested intents.
     *
     * @param request the request value
     * @return the resolved requested intents
     */
    private List<ToolIntentSpec> resolveRequestedIntents(InteractionRequest request) {
        return MCP_TOOL_INTENTS.stream()
            .filter(spec -> isIntentRequested(request, spec.inputKey()))
            .toList();
    }

    /**
     * Resolves the tool activations.
     *
     * @param requestedIntents the requested intents value
     * @param skill the skill value
     * @return the resolved tool activations
     */
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

    /**
     * Resolves the mcp tool.
     *
     * @param spec the spec value
     * @return the resolved mcp tool
     */
    private List<String> resolveMcpTool(ToolIntentSpec spec) {
        return mcpToolRegistryBridge.listRegisteredTools().stream()
            .filter(tool -> matchesMcpToolIntent(tool, spec))
            .map(McpToolRegistryBridge.RegisteredMcpTool::localToolName)
            .filter(toolRegistry::hasTool)
            .sorted()
            .toList();
    }

    /**
     * Returns whether matches mcp tool intent.
     *
     * @param tool the tool value
     * @param spec the spec value
     * @return whether the condition is satisfied
     */
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

    /**
     * Returns whether is tool enabled for skill.
     *
     * @param skill the skill value
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
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

    /**
     * Performs the merge requested tools operation.
     *
     * @param tools the tools value
     * @param requiredTools the required tools value
     * @param requestedIntents the requested intents value
     * @return the operation result
     */
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

    /**
     * Returns whether is intent requested.
     *
     * @param request the request value
     * @param inputKey the input key value
     * @return whether the condition is satisfied
     */
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

    /**
     * Performs the select relevant tools operation.
     *
     * @param request the request value
     * @param skill the skill value
     * @param availableTools the available tools value
     * @param requiredTools the required tools value
     * @return the operation result
     */
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

    /**
     * Resolves the max relevant mcp tools.
     *
     * @param skill the skill value
     * @return the resolved max relevant mcp tools
     */
    private int resolveMaxRelevantMcpTools(SkillDefinition skill) {
        if (skill == null || skill.routingSettings() == null || skill.routingSettings().maxRelevantMcpTools() == null) {
            return DEFAULT_MAX_RELEVANT_MCP_TOOLS;
        }
        return Math.max(1, Math.min(MAX_RELEVANT_MCP_TOOLS_LIMIT, skill.routingSettings().maxRelevantMcpTools()));
    }

    /**
     * Performs the score tool for query operation.
     *
     * @param toolName the tool name value
     * @param query the query value
     * @return the operation result
     */
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

    /**
     * Searches the able tool text.
     *
     * @param toolName the tool name value
     * @return the operation result
     */
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

    /**
     * Appends the search text.
     *
     * @param text the text value
     * @param value the value value
     */
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

    /**
     * Queries the terms.
     *
     * @param query the query value
     * @return the operation result
     */
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

    /**
     * Returns whether contains cjk.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
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

    /**
     * Normalizes the search text.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeSearchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * Returns whether has text.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Normalizes the tool names.
     *
     * @param tools the tools value
     * @return the operation result
     */
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

    private List<String> mergeToolNames(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        normalizeToolNames(first).forEach(merged::add);
        normalizeToolNames(second).forEach(merged::add);
        return new ArrayList<>(merged);
    }

    private String normalizeToolName(String toolName) {
        return toolName == null || toolName.isBlank()
            ? ""
            : toolName.trim().toLowerCase(Locale.ROOT);
    }

    private String firstText(Object first, Object second) {
        String firstValue = first == null ? null : String.valueOf(first).trim();
        if (firstValue != null && !firstValue.isBlank()) {
            return firstValue;
        }
        String secondValue = second == null ? null : String.valueOf(second).trim();
        return secondValue == null || secondValue.isBlank() ? null : secondValue;
    }

    /**
     * Returns whether is mcp tool name.
     *
     * @param toolName the tool name value
     * @param registeredMcpToolNames the registered mcp tool names value
     * @return whether the condition is satisfied
     */
    private boolean isMcpToolName(String toolName, Set<String> registeredMcpToolNames) {
        return toolName != null
            && (toolName.startsWith("mcp_")
            || (registeredMcpToolNames != null && registeredMcpToolNames.contains(toolName)));
    }

    /**
     * Returns whether has mcp binding.
     *
     * @param skill the skill value
     * @return whether the condition is satisfied
     */
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
        Map<String, String> skippedToolReasons,
        List<String> workflowAutoAddedTools
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

    private record WorkflowToolResolution(
        List<String> requiredTools,
        List<String> autoAddedTools,
        Map<String, String> skippedToolReasons
    ) {
    }
}
