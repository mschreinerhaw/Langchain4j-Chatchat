package com.chatchat.api.sidebar;

import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds right-side sidebar card data for the enterprise chat page.
 */
@Service
public class SidebarCardService {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SkillCatalogService skillCatalogService;
    private final McpToolCatalogQueryPort mcpCatalog;

    /**
     * Creates a new SidebarCardService instance.
     *
     * @param skillCatalogService the skill catalog service value
     * @param mcpCatalog the MCP catalog query port
     */
    public SidebarCardService(SkillCatalogService skillCatalogService,
                              McpToolCatalogQueryPort mcpCatalog) {
        this.skillCatalogService = skillCatalogService;
        this.mcpCatalog = mcpCatalog;
    }

    /**
     * Builds the sidebar.
     *
     * @param skillId the skill id value
     * @param conversationId the conversation id value
     * @return the built sidebar
     */
    public SidebarPayload buildSidebar(String skillId, String conversationId) {
        SkillDefinition skill = skillCatalogService.resolve(skillId);
        List<McpToolCatalogQueryPort.ServiceSummary> enabledServices = mcpCatalog.enabledServices();
        List<McpToolCatalogQueryPort.RegisteredTool> registeredTools = mcpCatalog.registeredTools();

        return new SidebarPayload(
            buildServiceUsage(skill, enabledServices, registeredTools),
            buildDataSources(enabledServices),
            buildQuickActions(skill),
            buildRecommendations(skill, 0),
            buildPermissionInfo(skill, enabledServices),
            conversationId
        );
    }

    /**
     * Performs the rotate recommendations operation.
     *
     * @param skillId the skill id value
     * @param cursor the cursor value
     * @return the operation result
     */
    public List<RecommendationItem> rotateRecommendations(String skillId, int cursor) {
        SkillDefinition skill = skillCatalogService.resolve(skillId);
        return buildRecommendations(skill, Math.max(cursor, 0));
    }

    /**
     * Executes the action.
     *
     * @param request the request value
     * @return the operation result
     */
    public SidebarActionResult executeAction(SidebarActionRequest request) {
        String actionId = request == null || request.actionId() == null ? "" : request.actionId().trim().toLowerCase(Locale.ROOT);
        String requestId = UUID.randomUUID().toString();
        SkillDefinition skill = skillCatalogService.resolve(request == null ? null : request.skillId());
        Map<String, Object> configuredAction = configuredItems(skill, "quickActions").stream()
            .filter(item -> actionId.equals(stringValue(item.get("actionId"))))
            .findFirst()
            .orElse(null);
        if (configuredAction == null) {
            return new SidebarActionResult(
                actionId,
                requestId,
                "该操作未在当前技能中配置。",
                "notify",
                null,
                Map.of("status", "UNSUPPORTED")
            );
        }
        return new SidebarActionResult(
            actionId,
            requestId,
            safe(stringValue(configuredAction.get("message")), "已加载技能配置操作。"),
            safe(stringValue(configuredAction.get("clientAction")), "notify"),
            blankToNull(stringValue(configuredAction.get("query"))),
            mapValue(configuredAction.get("payload"))
        );
    }

    /**
     * Builds the service usage.
     *
     * @param skill the skill value
     * @param enabledServices the enabled services value
     * @param registeredTools the registered tools value
     * @return the built service usage
     */
    private ServiceUsageCard buildServiceUsage(SkillDefinition skill,
                                               List<McpToolCatalogQueryPort.ServiceSummary> enabledServices,
                                               List<McpToolCatalogQueryPort.RegisteredTool> registeredTools) {
        List<ServiceUsageItem> items = new ArrayList<>();
        List<String> preferredServiceIds = skill.boundMcpServiceIds() == null ? List.of() : skill.boundMcpServiceIds();
        List<String> preferredToolNames = skill.boundMcpToolNames() == null ? List.of() : skill.boundMcpToolNames();

        for (String serviceId : preferredServiceIds) {
            enabledServices.stream()
                .filter(service -> service.id() != null && service.id().equals(serviceId))
                .findFirst()
                .ifPresent(service -> items.add(toUsageItem(service, null)));
        }

        for (String toolName : preferredToolNames) {
            registeredTools.stream()
                .filter(tool -> tool.localToolName() != null && tool.localToolName().equals(toolName))
                .findFirst()
                .ifPresent(tool -> {
                    if (items.stream().noneMatch(item -> item.key().equals(tool.localToolName()))) {
                        items.add(toUsageItem(null, tool));
                    }
                });
        }

        if (items.isEmpty()) {
            enabledServices.stream()
                .sorted(Comparator.comparing(McpToolCatalogQueryPort.ServiceSummary::updatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(2)
                .forEach(service -> items.add(toUsageItem(service, null)));
        }

        return new ServiceUsageCard(
            "本次服务使用",
            "共调用 " + items.size() + " 个服务",
            "查看完整调用链路",
            items
        );
    }

    /**
     * Converts the value to usage item.
     *
     * @param service the service value
     * @param tool the tool value
     * @return the converted usage item
     */
    private ServiceUsageItem toUsageItem(McpToolCatalogQueryPort.ServiceSummary service,
                                         McpToolCatalogQueryPort.RegisteredTool tool) {
        String key = tool != null ? tool.localToolName() : safe(service == null ? null : service.id(), "service-preview");
        String serviceName = tool != null
            ? safe(tool.serviceName(), "业务服务")
            : safe(service == null ? null : service.name(), "业务服务");
        String title = tool != null
            ? normalizeUsageTitle(tool.remoteToolName(), serviceName)
            : normalizeUsageTitle(serviceName, serviceName);
        String updatedAt = formatTime(tool == null && service != null ? service.updatedAt() : Instant.now());
        return new ServiceUsageItem(
            key,
            title,
            serviceName,
            "已就绪",
            true,
            iconTypeFor(title + " " + serviceName),
            "更新时间：" + updatedAt,
            "耗时：--"
        );
    }

    /**
     * Builds the data sources.
     *
     * @param enabledServices the enabled services value
     * @return the built data sources
     */
    private DataSourceCard buildDataSources(List<McpToolCatalogQueryPort.ServiceSummary> enabledServices) {
        List<DataSourceItem> items = enabledServices.stream()
            .sorted(Comparator.comparing(McpToolCatalogQueryPort.ServiceSummary::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(4)
            .map(service -> new DataSourceItem(
                safe(service.id(), UUID.randomUUID().toString()),
                safe(service.name(), "业务数据源"),
                service.enabled() ? "实时数据" : "待接入",
                iconTypeFor(service.name())
            ))
            .toList();

        Instant latestUpdatedAt = enabledServices.stream()
            .map(McpToolCatalogQueryPort.ServiceSummary::updatedAt)
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(Instant.now());

        return new DataSourceCard(
            "数据来源",
            items,
            "更新时间：" + formatDateTime(latestUpdatedAt)
        );
    }

    /**
     * Builds the quick actions.
     *
     * @param skill the skill value
     * @return the built quick actions
     */
    private List<QuickActionItem> buildQuickActions(SkillDefinition skill) {
        return configuredItems(skill, "quickActions").stream()
            .map(item -> new QuickActionItem(
                stringValue(item.get("actionId")),
                stringValue(item.get("label")),
                safe(stringValue(item.get("iconType")), "action")
            ))
            .filter(item -> !item.actionId().isBlank() && !item.label().isBlank())
            .toList();
    }

    /**
     * Builds the recommendations.
     *
     * @param skill the skill value
     * @param cursor the cursor value
     * @return the built recommendations
     */
    private List<RecommendationItem> buildRecommendations(SkillDefinition skill, int cursor) {
        List<RecommendationItem> pool = recommendationPool(skill);
        if (pool.size() <= 3) {
            return pool;
        }
        int safeCursor = cursor % pool.size();
        List<RecommendationItem> rotated = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            rotated.add(pool.get((safeCursor + index) % pool.size()));
        }
        return rotated;
    }

    /**
     * Performs the recommendation pool operation.
     *
     * @param skill the skill value
     * @return the operation result
     */
    private List<RecommendationItem> recommendationPool(SkillDefinition skill) {
        List<RecommendationItem> configured = configuredItems(skill, "recommendations").stream()
            .map(item -> new RecommendationItem(
                stringValue(item.get("id")),
                stringValue(item.get("label")),
                stringValue(item.get("description")),
                stringValue(item.get("queryTemplate"))
            ))
            .filter(item -> !item.id().isBlank() && !item.label().isBlank() && !item.queryTemplate().isBlank())
            .toList();
        if (!configured.isEmpty()) {
            return configured;
        }
        if (skill == null || skill.quickQuestions() == null) {
            return List.of();
        }
        String description = safe(skill.description(), "来自当前技能配置");
        List<RecommendationItem> derived = new ArrayList<>();
        for (int index = 0; index < skill.quickQuestions().size(); index++) {
            String question = safe(skill.quickQuestions().get(index), "");
            if (!question.isBlank()) {
                derived.add(new RecommendationItem(
                    "quick-question-" + (index + 1),
                    compactLabel(question),
                    description,
                    question
                ));
            }
        }
        return derived;
    }

    /**
     * Builds the permission info.
     *
     * @param skill the skill value
     * @param enabledServices the enabled services value
     * @return the built permission info
     */
    private PermissionInfo buildPermissionInfo(SkillDefinition skill,
                                               List<McpToolCatalogQueryPort.ServiceSummary> enabledServices) {
        return new PermissionInfo(
            "当前仅展示已授权且已登记的服务能力。",
            "查看我的服务权限",
            "./mcp.html"
        );
    }

    /**
     * Normalizes the usage title.
     *
     * @param rawTitle the raw title value
     * @param fallbackServiceName the fallback service name value
     * @return the operation result
     */
    private String normalizeUsageTitle(String rawTitle, String fallbackServiceName) {
        return safe(rawTitle, fallbackServiceName);
    }

    /**
     * Performs the icon type for operation.
     *
     * @param text the text value
     * @return the operation result
     */
    private String iconTypeFor(String text) {
        return "data";
    }

    private List<Map<String, Object>> configuredItems(SkillDefinition skill, String key) {
        if (skill == null || skill.workflowConfig() == null) {
            return List.of();
        }
        Map<String, Object> sidebar = mapValue(skill.workflowConfig().get("sidebar"));
        Object rawItems = sidebar.get(key);
        if (!(rawItems instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(this::mapValue)
            .filter(item -> !item.isEmpty())
            .toList();
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, entryValue) -> {
            if (key != null) {
                result.put(String.valueOf(key), entryValue);
            }
        });
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String compactLabel(String value) {
        String normalized = safe(value, "");
        return normalized.length() <= 24 ? normalized : normalized.substring(0, 24) + "…";
    }

    /**
     * Performs the format date time operation.
     *
     * @param instant the instant value
     * @return the operation result
     */
    private String formatDateTime(Instant instant) {
        return DATE_TIME_FORMATTER.format(instant.atZone(ZONE_ID));
    }

    /**
     * Performs the format time operation.
     *
     * @param instant the instant value
     * @return the operation result
     */
    private String formatTime(Instant instant) {
        return TIME_FORMATTER.format(instant.atZone(ZONE_ID));
    }

    /**
     * Performs the safe operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record SidebarPayload(
        ServiceUsageCard serviceUsage,
        DataSourceCard dataSources,
        List<QuickActionItem> quickActions,
        List<RecommendationItem> recommendedServices,
        PermissionInfo permissionInfo,
        String conversationId
    ) {
    }

    public record ServiceUsageCard(
        String title,
        String subtitle,
        String viewAllLabel,
        List<ServiceUsageItem> items
    ) {
    }

    public record ServiceUsageItem(
        String key,
        String title,
        String sourceName,
        String statusText,
        boolean success,
        String iconType,
        String updateLabel,
        String durationLabel
    ) {
    }

    public record DataSourceCard(
        String title,
        List<DataSourceItem> items,
        String updateTimeLabel
    ) {
    }

    public record DataSourceItem(
        String key,
        String name,
        String statusText,
        String iconType
    ) {
    }

    public record QuickActionItem(
        String actionId,
        String label,
        String iconType
    ) {
    }

    public record RecommendationItem(
        String id,
        String label,
        String description,
        String queryTemplate
    ) {
    }

    public record PermissionInfo(
        String description,
        String linkLabel,
        String linkUrl
    ) {
    }

    public record SidebarActionRequest(
        String actionId,
        String skillId,
        String conversationId,
        String latestQuestion,
        String latestAnswer
    ) {
    }

    public record SidebarActionResult(
        String actionId,
        String requestId,
        String message,
        String clientAction,
        String query,
        Map<String, Object> payload
    ) {
    }
}
