package com.chatchat.api.controller;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import com.chatchat.knowledgebase.search.LibraryDocumentItem;
import com.chatchat.knowledgebase.search.SearchService;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.skills.SkillRoutingSettings;
import com.chatchat.chat.skills.SkillToolConfig;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final EnterpriseAdminService enterpriseAdminService;

    /**
     * Returns the workshop.
     *
     * @param keyword the keyword value
     * @param category the category value
     * @param status the status value
     * @param model the model value
     * @param page the page value
     * @param pageSize the page size value
     * @return the workshop
     */
    @GetMapping
    @Operation(summary = "Get Agent workshop overview")
    public ApiResponse<WorkshopPayload> getWorkshop(@RequestParam(value = "keyword", required = false) String keyword,
                                                    @RequestParam(value = "category", required = false) String category,
                                                    @RequestParam(value = "status", required = false) String status,
                                                    @RequestParam(value = "model", required = false) String model,
                                                    @RequestParam(value = "page", required = false) Integer page,
                                                    @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                    HttpServletRequest request) {
        List<String> availableTools = availableTools();
        Map<String, List<String>> mcpToolsByServiceId = mcpToolsByServiceId();
        List<AgentCard> allAgents = skillCatalogService.list().stream()
            .map(skill -> toAgentCard(skill, availableTools, mcpToolsByServiceId))
            .filter(agent -> canCurrentUserViewAgent(request, agent.id(), agent.marketStatus()))
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

    /**
     * Returns the agent.
     *
     * @param agentId the agent id value
     * @return the agent
     */
    @GetMapping("/{agentId}")
    @Operation(summary = "Get one Agent workshop configuration")
    public ApiResponse<AgentCard> getAgent(@PathVariable("agentId") String agentId,
                                           HttpServletRequest request) {
        SkillDefinition skill = skillCatalogService.resolve(agentId);
        if (!canCurrentUserViewAgent(request, skill.id(), skill.marketStatus())) {
            return ApiResponse.badRequest("Current role is not allowed to use this Agent");
        }
        return ApiResponse.success(toAgentCard(skill, availableTools(), mcpToolsByServiceId()));
    }

    /**
     * Creates the agent.
     *
     * @param request the request value
     * @return the created agent
     */
    @PostMapping
    @Operation(summary = "Create one workshop Agent")
    public ApiResponse<AgentCard> createAgent(@RequestBody AgentUpsertRequest request) {
        SkillDefinition saved = skillCatalogService.upsert(toSkillDefinition(request, null));
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Agent created");
    }

    /**
     * Updates the agent.
     *
     * @param agentId the agent id value
     * @param request the request value
     * @return the updated agent
     */
    @PutMapping("/{agentId}")
    @Operation(summary = "Update one workshop Agent")
    public ApiResponse<AgentCard> updateAgent(@PathVariable("agentId") String agentId,
                                              @RequestBody AgentUpsertRequest request) {
        SkillDefinition saved = skillCatalogService.upsert(toSkillDefinition(request, agentId));
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Agent updated");
    }

    /**
     * Deletes the agent.
     *
     * @param agentId the agent id value
     * @return the operation result
     */
    @DeleteMapping("/{agentId}")
    @Operation(summary = "Delete one custom workshop Agent")
    public ApiResponse<Void> deleteAgent(@PathVariable("agentId") String agentId) {
        boolean deleted = skillCatalogService.delete(agentId);
        return ApiResponse.success(null, deleted ? "Agent deleted" : "Agent not found");
    }

    /**
     * Publishes the agent.
     *
     * @param agentId the agent id value
     * @return the operation result
     */
    @PostMapping("/{agentId}/publish")
    @Operation(summary = "Publish one Agent to capability market")
    public ApiResponse<AgentCard> publishAgent(@PathVariable("agentId") String agentId) {
        SkillDefinition saved = skillCatalogService.publishToMarket(agentId);
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Agent published");
    }

    /**
     * Performs the recall agent operation.
     *
     * @param agentId the agent id value
     * @return the operation result
     */
    @PostMapping("/{agentId}/recall")
    @Operation(summary = "Recall one Agent from capability market")
    public ApiResponse<AgentCard> recallAgent(@PathVariable("agentId") String agentId) {
        SkillDefinition saved = skillCatalogService.recallFromMarket(agentId);
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Agent recalled");
    }

    /**
     * Sets the default Agent capability.
     *
     * @param agentId the agent id value
     * @return the operation result
     */
    @PostMapping("/{agentId}/default")
    @Operation(summary = "Set one Agent as default capability")
    public ApiResponse<AgentCard> setDefaultAgent(@PathVariable("agentId") String agentId) {
        SkillDefinition saved = skillCatalogService.setDefaultAgent(agentId);
        return ApiResponse.success(toAgentCard(saved, availableTools(), mcpToolsByServiceId()), "Default Agent updated");
    }

    /**
     * Converts the value to agent card.
     *
     * @param skill the skill value
     * @param availableTools the available tools value
     * @param mcpToolsByServiceId the mcp tools by service id value
     * @return the converted agent card
     */
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
            skill.workflowConfig(),
            skill.defaultDataAsset(),
            skill.assetSelectionPolicy(),
            skill.quickQuestions(),
            skill.marketStatus(),
            marketStatusLabel(skill.marketStatus()),
            Boolean.TRUE.equals(skill.defaultAgent()),
            resolvedTools,
            resolvedTools.size(),
            skill.boundMcpServiceIds() == null ? 0 : skill.boundMcpServiceIds().size(),
            skill.boundDocumentIds() == null ? 0 : skill.boundDocumentIds().size(),
            skillCatalogService.isBuiltinSkill(skill.id()),
            skillCatalogService.editableFields(skill.id())
        );
    }

    /**
     * Resolves the status.
     *
     * @param skill the skill value
     * @param resolvedTools the resolved tools value
     * @param explicitlyBoundTools the explicitly bound tools value
     * @return the resolved status
     */
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

    /**
     * Returns whether matches agent filters.
     *
     * @param agent the agent value
     * @param keyword the keyword value
     * @param category the category value
     * @param status the status value
     * @param model the model value
     * @return whether the condition is satisfied
     */
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
            || ("default".equals(normalizedStatus) && agent.defaultAgent())
            || ("custom".equals(normalizedStatus) && !agent.builtin());
        boolean modelMatched = normalizedModel.isEmpty() || containsIgnoreCase(agent.modelName(), normalizedModel);
        return keywordMatched && categoryMatched && statusMatched && modelMatched;
    }

    /**
     * Performs the agent search text operation.
     *
     * @param agent the agent value
     * @return the operation result
     */
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

    /**
     * Appends the all.
     *
     * @param target the target value
     * @param values the values value
     */
    private void appendAll(List<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }

    /**
     * Returns whether list contains ignore case.
     *
     * @param values the values value
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean listContainsIgnoreCase(List<String> values, String keyword) {
        return values != null && values.stream().anyMatch(value -> containsIgnoreCase(value, keyword));
    }

    /**
     * Returns whether contains ignore case.
     *
     * @param value the value value
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    /**
     * Normalizes the keyword.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeKeyword(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())
            ? ""
            : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Normalizes the page.
     *
     * @param page the page value
     * @return the operation result
     */
    private int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    /**
     * Normalizes the page size.
     *
     * @param pageSize the page size value
     * @param defaultSize the default size value
     * @param maxSize the max size value
     * @return the operation result
     */
    private int normalizePageSize(Integer pageSize, int defaultSize, int maxSize) {
        int value = pageSize == null || pageSize <= 0 ? defaultSize : pageSize;
        return Math.min(value, maxSize);
    }

    /**
     * Performs the page offset operation.
     *
     * @param page the page value
     * @param pageSize the page size value
     * @return the operation result
     */
    private long pageOffset(int page, int pageSize) {
        return (long) Math.max(0, page - 1) * Math.max(1, pageSize);
    }

    /**
     * Converts the value to tal pages.
     *
     * @param total the total value
     * @param pageSize the page size value
     * @return the converted tal pages
     */
    private int totalPages(int total, int pageSize) {
        return Math.max(1, (int) Math.ceil((double) Math.max(0, total) / Math.max(1, pageSize)));
    }

    /**
     * Performs the agent categories operation.
     *
     * @param agents the agents value
     * @return the operation result
     */
    private List<String> agentCategories(List<AgentCard> agents) {
        return agents.stream()
            .flatMap(agent -> agent.skillTags() == null ? java.util.stream.Stream.empty() : agent.skillTags().stream())
            .filter(tag -> tag != null && !tag.isBlank())
            .distinct()
            .sorted(String::compareToIgnoreCase)
            .toList();
    }

    /**
     * Performs the available tools operation.
     *
     * @return the operation result
     */
    private List<String> availableTools() {
        return toolRegistry.getAllToolNames().stream()
            .filter(name -> name != null && !name.isBlank())
            .filter(this::isUserVisibleAgentTool)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private boolean canCurrentUserViewAgent(HttpServletRequest request, String agentId, String marketStatus) {
        String currentUserId = currentUserId(request);
        if (currentUserId == null) {
            return true;
        }
        if (enterpriseAdminService.hasAllAgentAccess(currentUserId)) {
            return true;
        }
        if (!"published".equalsIgnoreCase(marketStatus)) {
            return false;
        }
        return enterpriseAdminService.canAccessAgent(currentUserId, agentId);
    }

    private String currentUserId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Returns whether is user visible agent tool.
     *
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean isUserVisibleAgentTool(String toolName) {
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        return metadata == null || (metadata.isAgentCompatible() && metadata.isUserVisible());
    }

    /**
     * Performs the mcp tools by service id operation.
     *
     * @return the operation result
     */
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

    /**
     * Converts the value to skill definition.
     *
     * @param request the request value
     * @param pathAgentId the path agent id value
     * @return the converted skill definition
     */
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
            request.getWorkflowConfig(),
            request.getDefaultDataAsset(),
            request.getAssetSelectionPolicy(),
            request.getQuickQuestions(),
            request.getMarketStatus(),
            request.getDefaultAgent()
        );
    }

    /**
     * Resolves the bound model name.
     *
     * @param requestedModelName the requested model name value
     * @return the resolved bound model name
     */
    private String resolveBoundModelName(String requestedModelName) {
        if (requestedModelName != null
            && !requestedModelName.isBlank()
            && !"default".equalsIgnoreCase(requestedModelName.trim())) {
            return requestedModelName.trim();
        }
        return modelsConfig.getDefaultChatModel();
    }

    /**
     * Performs the model options operation.
     *
     * @return the operation result
     */
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

    /**
     * Performs the market status label operation.
     *
     * @param marketStatus the market status value
     * @return the operation result
     */
    private String marketStatusLabel(String marketStatus) {
        if ("published".equalsIgnoreCase(marketStatus)) {
            return "已发布";
        }
        if ("recalled".equalsIgnoreCase(marketStatus)) {
            return "已回收";
        }
        return "未发布";
    }

    /**
     * Performs the short name operation.
     *
     * @param label the label value
     * @param fallback the fallback value
     * @return the operation result
     */
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
        private Map<String, Object> workflowConfig;
        private SkillDefinition.DefaultDataAsset defaultDataAsset;
        private SkillDefinition.AssetSelectionPolicy assetSelectionPolicy;
        private List<String> quickQuestions;
        private String marketStatus;
        private Boolean defaultAgent;
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

    @Data
    @NoArgsConstructor
    public static class WorkshopSummary {
        private int agentCount;
        private int builtinCount;
        private int customCount;
        private int publishedCount;
        private int unpublishedCount;
        private int availableToolCount;
        private int registeredMcpToolCount;

        /**
         * Creates a new AgentWorkshopController instance.
         *
         * @param agentCount the agent count value
         * @param builtinCount the builtin count value
         * @param customCount the custom count value
         * @param publishedCount the published count value
         * @param unpublishedCount the unpublished count value
         * @param availableToolCount the available tool count value
         * @param registeredMcpToolCount the registered mcp tool count value
         */
        public WorkshopSummary(int agentCount,
                               int builtinCount,
                               int customCount,
                               int publishedCount,
                               int unpublishedCount,
                               int availableToolCount,
                               int registeredMcpToolCount) {
            this.agentCount = agentCount;
            this.builtinCount = builtinCount;
            this.customCount = customCount;
            this.publishedCount = publishedCount;
            this.unpublishedCount = unpublishedCount;
            this.availableToolCount = availableToolCount;
            this.registeredMcpToolCount = registeredMcpToolCount;
        }
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
        Map<String, Object> workflowConfig,
        SkillDefinition.DefaultDataAsset defaultDataAsset,
        SkillDefinition.AssetSelectionPolicy assetSelectionPolicy,
        List<String> quickQuestions,
        String marketStatus,
        String marketStatusLabel,
        boolean defaultAgent,
        List<String> resolvedToolNames,
        int resolvedToolCount,
        int boundServiceCount,
        int boundDocumentCount,
        boolean builtin,
        List<String> editableFields
    ) {
    }
}
