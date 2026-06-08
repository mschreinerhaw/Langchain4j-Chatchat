package com.chatchat.chat.interaction.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves runtime tool policy before the agent loop starts.
 */
@Component
@RequiredArgsConstructor
public class AgentToolPolicyResolver {

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

        List<ToolIntentSpec> requestedIntents = resolveRequestedIntents(request);
        List<ToolActivation> activations = resolveToolActivations(requestedIntents, skill);
        List<String> requiredTools = activations.stream()
            .map(ToolActivation::localToolName)
            .toList();
        availableTools = mergeRequestedTools(availableTools, requiredTools, requestedIntents);

        return new ToolPolicy(
            availableTools,
            requiredTools,
            hasMcpBinding(skill),
            activations.stream().map(ToolActivation::intentName).toList()
        );
    }

    private List<String> discoverDefaultTools(String skillId) {
        Map<String, List<String>> mcpToolsByServiceId = new LinkedHashMap<>();
        mcpToolRegistryBridge.listRegisteredTools().forEach(tool ->
            mcpToolsByServiceId.computeIfAbsent(tool.serviceId(), ignored -> new ArrayList<>())
                .add(tool.localToolName())
        );
        return skillCatalogService.resolveTools(skillId, toolRegistry.getAllToolNames(), mcpToolsByServiceId);
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
        List<String> activatedIntents
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
}
