package com.chatchat.api.controller;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.mcp.service.McpToolRegistryBridge;
import com.chatchat.api.search.LibraryDocumentItem;
import com.chatchat.api.search.SearchService;
import com.chatchat.api.skills.SkillCatalogService;
import com.chatchat.api.skills.SkillDefinition;
import com.chatchat.api.skills.SkillRoutingSettings;
import com.chatchat.api.skills.SkillToolConfig;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.models.config.ModelsConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Business-facing Agent workshop APIs backed by the persistent skill catalog.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/agents/workshop")
@Tag(name = "Agent Workshop", description = "Manage reusable business agents")
public class AgentWorkshopController {

    private final SkillCatalogService skillCatalogService;
    private final ToolRegistry toolRegistry;
    private final McpToolRegistryBridge registryBridge;
    private final ModelsConfig modelsConfig;
    private final SearchService searchService;

    @GetMapping
    @Operation(summary = "Get Agent workshop overview")
    public ApiResponse<WorkshopPayload> getWorkshop(@RequestParam(value = "keyword", required = false) String keyword,
                                                    @RequestParam(value = "category", required = false) String category,
                                                    @RequestParam(value = "status", required = false) String status,
                                                    @RequestParam(value = "model", required = false) String model,
                                                    @RequestParam(value = "page", required = false) Integer page,
                                                    @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        List<String> availableTools = availableTools();
        Map<String, List<String>> mcpToolsByServiceId = mcpToolsByServiceId();
        List<AgentCard> allAgents = skillCatalogService.list().stream()
            .map(skill -> toAgentCard(skill, availableTools, mcpToolsByServiceId))
            .toList();
        List<AgentCard> filteredAgents = allAgents.stream()
            .filter(agent -> matchesAgentFilters(agent, keyword, category, status, model))
            .toList();
        PageInfo pageInfo = null;
        List<AgentCard> agents = filteredAgents;
        if (page != null || pageSize != null) {
            int normalizedPage = normalizePage(page);
            int normalizedPageSize = normalizePageSize(pageSize, 5, 100);
            agents = filteredAgents.stream()
                .skip(pageOffset(normalizedPage, normalizedPageSize))
                .limit(normalizedPageSize)
                .toList();
            pageInfo = new PageInfo(
                filteredAgents.size(),
                normalizedPage,
                normalizedPageSize,
                totalPages(filteredAgents.size(), normalizedPageSize)
            );
        }
        WorkshopSummary summary = new WorkshopSummary(
            allAgents.size(),
            (int) allAgents.stream().filter(AgentCard::builtin).count(),
            (int) allAgents.stream().filter(agent -> !agent.builtin()).count(),
            (int) allAgents.stream().filter(agent -> "published".equalsIgnoreCase(agent.marketStatus())).count(),
            (int) allAgents.stream().filter(agent -> !"published".equalsIgnoreCase(agent.marketStatus())).count(),
            availableTools.size(),
            registryBridge.listRegisteredTools().size()
        );
        return ApiResponse.success(new WorkshopPayload(
            summary,
            agents,
            availableTools,
            registryBridge.listRegisteredTools(),
            modelOptions(),
            searchService.listLibrary("all", null, 1, 500).documents(),
            pageInfo,
            agentCategories(allAgents)
        ));
    }

    @GetMapping("/{agentId}")
    @Operation(summary = "Get one Agent workshop configuration")
    public ApiResponse<AgentCard> getAgent(@PathVariable("agentId") String agentId) {
        return ApiResponse.success(toAgentCard(skillCatalogService.resolve(agentId), availableTools(), mcpToolsByServiceId()));
    }

    @PostMapping
    @Operation(summary = "Create one workshop Agent")
    public ApiResponse<AgentCard> createAgent(@RequestBody AgentUpsertRequest request) {
        SkillDefinition saved = skillCatalogService.upsert(toSkillDefinition(request, null));
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Agent created");
    }

    @PutMapping("/{agentId}")
    @Operation(summary = "Update one workshop Agent")
    public ApiResponse<AgentCard> updateAgent(@PathVariable("agentId") String agentId,
                                              @RequestBody AgentUpsertRequest request) {
        SkillDefinition saved = skillCatalogService.upsert(toSkillDefinition(request, agentId));
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Agent updated");
    }

    @DeleteMapping("/{agentId}")
    @Operation(summary = "Delete one custom workshop Agent")
    public ApiResponse<Void> deleteAgent(@PathVariable("agentId") String agentId) {
        boolean deleted = skillCatalogService.delete(agentId);
        return ApiResponse.success(null, deleted ? "Agent deleted" : "Agent not found");
    }

    @PostMapping("/{agentId}/publish")
    @Operation(summary = "Publish one Agent to capability market")
    public ApiResponse<AgentCard> publishAgent(@PathVariable("agentId") String agentId) {
        SkillDefinition saved = skillCatalogService.publishToMarket(agentId);
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Agent published");
    }

    @PostMapping("/{agentId}/recall")
    @Operation(summary = "Recall one Agent from capability market")
    public ApiResponse<AgentCard> recallAgent(@PathVariable("agentId") String agentId) {
        SkillDefinition saved = skillCatalogService.recallFromMarket(agentId);
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Agent recalled");
    }

    private AgentCard toAgentCard(SkillDefinition skill,
                                  List<String> availableTools,
                                  Map<String, List<String>> mcpToolsByServiceId) {
        List<String> resolvedTools = skillCatalogService.resolveTools(skill.id(), availableTools, mcpToolsByServiceId);
        LinkedHashSet<String> explicitlyBoundTools = new LinkedHashSet<>();
        if (skill.boundMcpToolNames() != null) {
            explicitlyBoundTools.addAll(skill.boundMcpToolNames());
        }
        if (skill.toolConfigs() != null) {
            skill.toolConfigs().stream()
                .filter(config -> config != null && config.toolName() != null && Boolean.TRUE.equals(config.enabled()))
                .map(SkillToolConfig::toolName)
                .forEach(explicitlyBoundTools::add);
        }

        String status = resolveStatus(skill, resolvedTools, explicitlyBoundTools);
        return new AgentCard(
            skill.id(),
            shortName(skill.label(), skill.id()),
            skill.label(),
            skill.description(),
            status,
            skill.defaultMode(),
            skill.modelName(),
            skill.usageScenarios(),
            skill.skillTags(),
            skill.systemPrompt(),
            skill.firstUseGreeting(),
            skill.preferredToolPrefixes(),
            skill.boundMcpServiceIds(),
            List.copyOf(explicitlyBoundTools),
            skill.boundDocumentIds(),
            skill.boundDocumentTags(),
            skill.toolConfigs(),
            skill.routingSettings(),
            skill.quickQuestions(),
            skill.marketStatus(),
            marketStatusLabel(skill.marketStatus()),
            resolvedTools,
            resolvedTools.size(),
            skill.boundMcpServiceIds() == null ? 0 : skill.boundMcpServiceIds().size(),
            skill.boundDocumentIds() == null ? 0 : skill.boundDocumentIds().size(),
            skillCatalogService.isBuiltinSkill(skill.id()),
            skillCatalogService.editableFields(skill.id())
        );
    }

    private String resolveStatus(SkillDefinition skill, List<String> resolvedTools, LinkedHashSet<String> explicitlyBoundTools) {
        if (!"agent_chat".equalsIgnoreCase(skill.defaultMode())) {
            return "提示词助手";
        }
        if (!explicitlyBoundTools.isEmpty()) {
            return "已绑定工具";
        }
        if (resolvedTools == null || resolvedTools.isEmpty()) {
            return "待接入工具";
        }
        return "自动匹配工具";
    }

    private boolean matchesAgentFilters(AgentCard agent, String keyword, String category, String status, String model) {
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedCategory = normalizeKeyword(category);
        String normalizedStatus = normalizeKeyword(status);
        String normalizedModel = normalizeKeyword(model);
        boolean keywordMatched = normalizedKeyword.isEmpty() || agentSearchText(agent).contains(normalizedKeyword);
        boolean categoryMatched = normalizedCategory.isEmpty()
            || listContainsIgnoreCase(agent.skillTags(), normalizedCategory);
        boolean statusMatched = normalizedStatus.isEmpty()
            || ("published".equals(normalizedStatus) && "published".equalsIgnoreCase(agent.marketStatus()))
            || ("unpublished".equals(normalizedStatus) && !"published".equalsIgnoreCase(agent.marketStatus()))
            || ("builtin".equals(normalizedStatus) && agent.builtin())
            || ("custom".equals(normalizedStatus) && !agent.builtin());
        boolean modelMatched = normalizedModel.isEmpty() || containsIgnoreCase(agent.modelName(), normalizedModel);
        return keywordMatched && categoryMatched && statusMatched && modelMatched;
    }

    private String agentSearchText(AgentCard agent) {
        List<String> fields = new ArrayList<>();
        fields.add(agent.id());
        fields.add(agent.name());
        fields.add(agent.description());
        fields.add(agent.status());
        fields.add(agent.marketStatus());
        fields.add(agent.marketStatusLabel());
        fields.add(agent.defaultMode());
        fields.add(agent.modelName());
        appendAll(fields, agent.usageScenarios());
        appendAll(fields, agent.skillTags());
        appendAll(fields, agent.quickQuestions());
        appendAll(fields, agent.preferredToolPrefixes());
        appendAll(fields, agent.boundMcpServiceIds());
        appendAll(fields, agent.boundMcpToolNames());
        appendAll(fields, agent.boundDocumentIds());
        appendAll(fields, agent.boundDocumentTags());
        appendAll(fields, agent.resolvedToolNames());
        return fields.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.toLowerCase(java.util.Locale.ROOT))
            .reduce("", (left, right) -> left + " " + right);
    }

    private void appendAll(List<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }

    private boolean listContainsIgnoreCase(List<String> values, String keyword) {
        return values != null && values.stream().anyMatch(value -> containsIgnoreCase(value, keyword));
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    private String normalizeKeyword(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())
            ? ""
            : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    private int normalizePageSize(Integer pageSize, int defaultSize, int maxSize) {
        int value = pageSize == null || pageSize <= 0 ? defaultSize : pageSize;
        return Math.min(value, maxSize);
    }

    private long pageOffset(int page, int pageSize) {
        return (long) Math.max(0, page - 1) * Math.max(1, pageSize);
    }

    private int totalPages(int total, int pageSize) {
        return Math.max(1, (int) Math.ceil((double) Math.max(0, total) / Math.max(1, pageSize)));
    }

    private List<String> agentCategories(List<AgentCard> agents) {
        return agents.stream()
            .flatMap(agent -> agent.skillTags() == null ? java.util.stream.Stream.empty() : agent.skillTags().stream())
            .filter(tag -> tag != null && !tag.isBlank())
            .distinct()
            .sorted(String::compareToIgnoreCase)
            .toList();
    }

    private List<String> availableTools() {
        return toolRegistry.getAllToolNames().stream()
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private Map<String, List<String>> mcpToolsByServiceId() {
        Map<String, List<String>> toolsByService = new LinkedHashMap<>();
        for (McpToolRegistryBridge.RegisteredMcpTool tool : registryBridge.listRegisteredTools()) {
            if (tool.serviceId() == null || tool.localToolName() == null) {
                continue;
            }
            toolsByService.computeIfAbsent(tool.serviceId(), ignored -> new ArrayList<>()).add(tool.localToolName());
        }
        toolsByService.replaceAll((serviceId, tools) -> tools.stream().distinct().sorted().toList());
        return toolsByService;
    }

    private SkillDefinition toSkillDefinition(AgentUpsertRequest request, String pathAgentId) {
        if (request == null) {
            throw new IllegalArgumentException("agent payload is required");
        }
        String id = pathAgentId == null || pathAgentId.isBlank() ? request.getId() : pathAgentId;
        return new SkillDefinition(
            id,
            request.getName(),
            request.getDescription(),
            request.getUsageScenarios(),
            request.getSkillTags(),
            request.getDefaultMode(),
            resolveBoundModelName(request.getModelName()),
            request.getSystemPrompt(),
            request.getFirstUseGreeting(),
            request.getPreferredToolPrefixes(),
            request.getBoundMcpServiceIds(),
            request.getBoundMcpToolNames(),
            request.getBoundDocumentIds(),
            request.getBoundDocumentTags(),
            request.getToolConfigs(),
            request.getRoutingSettings(),
            request.getQuickQuestions(),
            request.getMarketStatus()
        );
    }

    private String resolveBoundModelName(String requestedModelName) {
        if (requestedModelName != null
            && !requestedModelName.isBlank()
            && !"default".equalsIgnoreCase(requestedModelName.trim())) {
            return requestedModelName.trim();
        }
        return modelsConfig.getDefaultChatModel();
    }

    private List<ModelOption> modelOptions() {
        List<String> candidates = new ArrayList<>(modelsConfig.getAvailableChatModels());
        if (modelsConfig.getDefaultChatModel() != null && !modelsConfig.getDefaultChatModel().isBlank()) {
            candidates.add(0, modelsConfig.getDefaultChatModel());
        }
        return candidates.stream()
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .map(name -> new ModelOption(name, name))
            .toList();
    }

    private String marketStatusLabel(String marketStatus) {
        if ("published".equalsIgnoreCase(marketStatus)) {
            return "已发布";
        }
        if ("recalled".equalsIgnoreCase(marketStatus)) {
            return "已回收";
        }
        return "未发布";
    }

    private String shortName(String label, String fallback) {
        String source = label == null || label.isBlank() ? fallback : label.trim();
        if (source == null || source.isBlank()) {
            return "A";
        }
        int codePointLength = Character.charCount(source.codePointAt(0));
        return source.substring(0, codePointLength).toUpperCase();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentUpsertRequest {
        private String id;
        private String name;
        private String description;
        private List<String> usageScenarios;
        private List<String> skillTags;
        private String defaultMode;
        private String modelName;
        private String systemPrompt;
        private String firstUseGreeting;
        private List<String> preferredToolPrefixes;
        private List<String> boundMcpServiceIds;
        private List<String> boundMcpToolNames;
        private List<String> boundDocumentIds;
        private List<String> boundDocumentTags;
        private List<SkillToolConfig> toolConfigs;
        private SkillRoutingSettings routingSettings;
        private List<String> quickQuestions;
        private String marketStatus;
    }

    public record WorkshopPayload(
        WorkshopSummary summary,
        List<AgentCard> agents,
        List<String> availableTools,
        List<McpToolRegistryBridge.RegisteredMcpTool> registeredMcpTools,
        List<ModelOption> models,
        List<LibraryDocumentItem> documents,
        PageInfo page,
        List<String> agentCategories
    ) {
    }

    public record PageInfo(
        int total,
        int page,
        int pageSize,
        int totalPages
    ) {
    }

    public record ModelOption(String value, String label) {
    }

    public record WorkshopSummary(
        int agentCount,
        int builtinCount,
        int customCount,
        int publishedCount,
        int unpublishedCount,
        int availableToolCount,
        int registeredMcpToolCount
    ) {
    }

    public record AgentCard(
        String id,
        String shortName,
        String name,
        String description,
        String status,
        String defaultMode,
        String modelName,
        List<String> usageScenarios,
        List<String> skillTags,
        String systemPrompt,
        String firstUseGreeting,
        List<String> preferredToolPrefixes,
        List<String> boundMcpServiceIds,
        List<String> boundMcpToolNames,
        List<String> boundDocumentIds,
        List<String> boundDocumentTags,
        List<SkillToolConfig> toolConfigs,
        SkillRoutingSettings routingSettings,
        List<String> quickQuestions,
        String marketStatus,
        String marketStatusLabel,
        List<String> resolvedToolNames,
        int resolvedToolCount,
        int boundServiceCount,
        int boundDocumentCount,
        boolean builtin,
        List<String> editableFields
    ) {
    }
}
