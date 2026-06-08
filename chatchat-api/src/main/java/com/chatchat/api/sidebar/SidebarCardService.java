package com.chatchat.api.sidebar;

import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.service.McpServiceConfigService;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final McpServiceConfigService mcpServiceConfigService;
    private final McpToolRegistryBridge mcpToolRegistryBridge;

    public SidebarCardService(SkillCatalogService skillCatalogService,
                              McpServiceConfigService mcpServiceConfigService,
                              McpToolRegistryBridge mcpToolRegistryBridge) {
        this.skillCatalogService = skillCatalogService;
        this.mcpServiceConfigService = mcpServiceConfigService;
        this.mcpToolRegistryBridge = mcpToolRegistryBridge;
    }

    public SidebarPayload buildSidebar(String skillId, String conversationId) {
        SkillDefinition skill = skillCatalogService.resolve(skillId);
        List<McpServiceConfig> enabledServices = mcpServiceConfigService.listEnabled();
        List<McpToolRegistryBridge.RegisteredMcpTool> registeredTools = mcpToolRegistryBridge.listRegisteredTools();

        return new SidebarPayload(
            buildServiceUsage(skill, enabledServices, registeredTools),
            buildDataSources(enabledServices),
            buildQuickActions(skill),
            buildRecommendations(skill, 0),
            buildPermissionInfo(skill, enabledServices),
            conversationId
        );
    }

    public List<RecommendationItem> rotateRecommendations(String skillId, int cursor) {
        SkillDefinition skill = skillCatalogService.resolve(skillId);
        return buildRecommendations(skill, Math.max(cursor, 0));
    }

    public SidebarActionResult executeAction(SidebarActionRequest request) {
        String actionId = request == null || request.actionId() == null ? "" : request.actionId().trim().toLowerCase(Locale.ROOT);
        String requestId = UUID.randomUUID().toString();
        return switch (actionId) {
            case "view_detail_metrics" -> new SidebarActionResult(
                actionId,
                requestId,
                "已为你整理详细指标，请继续查看。",
                "send_query",
                "请基于当前会话展开详细指标、波动原因和异常点。",
                Map.of("target", "detailed_metrics")
            );
            case "generate_analysis_report" -> new SidebarActionResult(
                actionId,
                requestId,
                "分析报告生成任务已创建。",
                "send_query",
                "请基于当前会话内容生成一份正式的分析报告，包含结论、风险点和后续建议。",
                Map.of(
                    "reportId", "REPORT-" + shortId(requestId),
                    "status", "QUEUED"
                )
            );
            case "export_excel" -> new SidebarActionResult(
                actionId,
                requestId,
                "Excel 导出任务已提交。",
                "notify",
                null,
                Map.of(
                    "exportTaskId", "EXPORT-" + shortId(requestId),
                    "fileName", "enterprise-analysis-" + System.currentTimeMillis() + ".xlsx",
                    "status", "QUEUED"
                )
            );
            case "notify_owner" -> new SidebarActionResult(
                actionId,
                requestId,
                "已通知对应负责人跟进。",
                "notify",
                null,
                Map.of(
                    "notifyTicketId", "NOTICE-" + shortId(requestId),
                    "owner", "风控负责人",
                    "status", "SENT"
                )
            );
            case "subscribe_monitor" -> new SidebarActionResult(
                actionId,
                requestId,
                "已加入持续监控。",
                "notify",
                null,
                Map.of(
                    "subscriptionId", "SUB-" + shortId(requestId),
                    "status", "ACTIVE"
                )
            );
            default -> new SidebarActionResult(
                actionId,
                requestId,
                "暂不支持该快捷操作。",
                "notify",
                null,
                Map.of("status", "UNSUPPORTED")
            );
        };
    }

    private ServiceUsageCard buildServiceUsage(SkillDefinition skill,
                                               List<McpServiceConfig> enabledServices,
                                               List<McpToolRegistryBridge.RegisteredMcpTool> registeredTools) {
        List<ServiceUsageItem> items = new ArrayList<>();
        List<String> preferredServiceIds = skill.boundMcpServiceIds() == null ? List.of() : skill.boundMcpServiceIds();
        List<String> preferredToolNames = skill.boundMcpToolNames() == null ? List.of() : skill.boundMcpToolNames();

        for (String serviceId : preferredServiceIds) {
            enabledServices.stream()
                .filter(service -> service.getId() != null && service.getId().equals(serviceId))
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
                .sorted(Comparator.comparing(McpServiceConfig::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(2)
                .forEach(service -> items.add(toUsageItem(service, null)));
        }

        if (items.isEmpty()) {
            items.addAll(defaultUsageItems());
        }

        return new ServiceUsageCard(
            "本次服务使用",
            "共调用 " + items.size() + " 个服务",
            "查看完整调用链路",
            items
        );
    }

    private ServiceUsageItem toUsageItem(McpServiceConfig service,
                                         McpToolRegistryBridge.RegisteredMcpTool tool) {
        String key = tool != null ? tool.localToolName() : safe(service == null ? null : service.getId(), "service-preview");
        String serviceName = tool != null
            ? safe(tool.serviceName(), "业务服务")
            : safe(service == null ? null : service.getName(), "业务服务");
        String title = tool != null
            ? normalizeUsageTitle(tool.remoteToolName(), serviceName)
            : normalizeUsageTitle(serviceName, serviceName);
        String updatedAt = formatTime(tool == null && service != null ? service.getUpdatedAt() : Instant.now());
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

    private List<ServiceUsageItem> defaultUsageItems() {
        return List.of(
            new ServiceUsageItem(
                "preview-risk",
                "风险预警指标服务",
                "实时风控系统",
                "已就绪",
                true,
                "shield",
                "更新时间：" + formatTime(Instant.now()),
                "耗时：--"
            ),
            new ServiceUsageItem(
                "preview-finance",
                "财务指标查询服务",
                "财务核心系统",
                "已就绪",
                true,
                "finance",
                "更新时间：" + formatTime(Instant.now()),
                "耗时：--"
            )
        );
    }

    private DataSourceCard buildDataSources(List<McpServiceConfig> enabledServices) {
        List<DataSourceItem> items = enabledServices.stream()
            .sorted(Comparator.comparing(McpServiceConfig::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(4)
            .map(service -> new DataSourceItem(
                safe(service.getId(), UUID.randomUUID().toString()),
                safe(service.getName(), "业务数据源"),
                service.isEnabled() ? "实时数据" : "待接入",
                iconTypeFor(service.getName())
            ))
            .toList();

        if (items.isEmpty()) {
            items = List.of(
                new DataSourceItem("source-risk", "实时风控系统", "实时数据", "shield"),
                new DataSourceItem("source-finance", "财务核心系统", "实时数据", "finance")
            );
        }

        Instant latestUpdatedAt = enabledServices.stream()
            .map(McpServiceConfig::getUpdatedAt)
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(Instant.now());

        return new DataSourceCard(
            "数据来源",
            items,
            "更新时间：" + formatDateTime(latestUpdatedAt)
        );
    }

    private List<QuickActionItem> buildQuickActions(SkillDefinition skill) {
        List<QuickActionItem> items = new ArrayList<>();
        items.add(new QuickActionItem("view_detail_metrics", "查看详细指标", "doc"));
        items.add(new QuickActionItem("generate_analysis_report", "生成分析报告", "report"));
        items.add(new QuickActionItem("export_excel", "导出Excel", "download"));
        items.add(new QuickActionItem("notify_owner", "推送负责人", "user"));
        items.add(new QuickActionItem("subscribe_monitor", "加入持续监控", "monitor"));

        if (skill != null && "report".equalsIgnoreCase(skill.id())) {
            items = List.of(
                new QuickActionItem("view_detail_metrics", "查看报告重点", "doc"),
                new QuickActionItem("generate_analysis_report", "生成会议纪要", "report"),
                new QuickActionItem("export_excel", "导出附件清单", "download"),
                new QuickActionItem("notify_owner", "推送审批人", "user"),
                new QuickActionItem("subscribe_monitor", "加入复盘跟踪", "monitor")
            );
        }
        return items;
    }

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

    private List<RecommendationItem> recommendationPool(SkillDefinition skill) {
        String id = skill == null || skill.id() == null ? "general" : skill.id().trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "risk" -> List.of(
                new RecommendationItem("risk-account", "异常账户分析", "快速排查高风险账户异动", "请帮我分析当前会话关联的异常账户。"),
                new RecommendationItem("risk-portrait", "客户风险画像", "生成客户风险等级与成因画像", "请根据当前上下文生成客户风险画像。"),
                new RecommendationItem("risk-watch", "风险事件追踪", "跟踪风险事件处置进度", "请汇总当前风险事件的处置进展和责任人。"),
                new RecommendationItem("risk-kpi", "风险指标看板", "查看风险关键指标变化", "请展示最近一周风险关键指标变化。")
            );
            case "operations" -> List.of(
                new RecommendationItem("ops-board", "经营指标看板", "查看核心经营指标波动", "请整理本周经营指标看板。"),
                new RecommendationItem("ops-funnel", "渠道转化诊断", "识别转化漏斗问题", "请分析当前业务的转化漏斗问题。"),
                new RecommendationItem("ops-report", "经营日报生成", "生成可汇报的经营日报", "请生成一份经营日报。"),
                new RecommendationItem("ops-branch", "营业部对比", "对比分支机构表现", "请对比各营业部最近的经营表现。")
            );
            case "report" -> List.of(
                new RecommendationItem("report-summary", "报告重点提炼", "抽取核心结论与建议", "请提炼当前报告的核心结论。"),
                new RecommendationItem("report-risk", "口径异常检查", "识别数据矛盾与异常", "请检查当前报告中的口径异常。"),
                new RecommendationItem("report-brief", "会议摘要输出", "整理成一页会议摘要", "请生成一页会议摘要。"),
                new RecommendationItem("report-action", "行动项清单", "沉淀后续跟进动作", "请列出基于当前报告的行动项。")
            );
            default -> List.of(
                new RecommendationItem("mix-account", "异常账户分析", "识别异常账户与风险点", "请帮我分析异常账户。"),
                new RecommendationItem("mix-portrait", "客户风险画像", "生成客户分层画像", "请生成客户风险画像。"),
                new RecommendationItem("mix-board", "经营指标看板", "快速查看经营重点指标", "请生成经营指标看板。"),
                new RecommendationItem("mix-report", "分析报告生成", "沉淀结构化分析报告", "请生成一份结构化分析报告。")
            );
        };
    }

    private PermissionInfo buildPermissionInfo(SkillDefinition skill, List<McpServiceConfig> enabledServices) {
        int serviceCount = enabledServices.size();
        List<String> scopes = new ArrayList<>();
        scopes.add("查询");
        if (skill != null && skill.boundMcpToolNames() != null && !skill.boundMcpToolNames().isEmpty()) {
            scopes.add("分析");
        }
        if (serviceCount > 2) {
            scopes.add("导出");
        }
        return new PermissionInfo(
            "当前结果基于已授权的服务生成，可用范围：" + String.join(" / ", new LinkedHashSet<>(scopes)),
            "查看我的服务权限",
            "./mcp.html"
        );
    }

    private String normalizeUsageTitle(String rawTitle, String fallbackServiceName) {
        String value = safe(rawTitle, fallbackServiceName);
        if (value.endsWith("服务") || value.endsWith("系统")) {
            return value;
        }
        if (value.contains("指标") || value.contains("分析") || value.contains("查询") || value.contains("画像")) {
            return value + "服务";
        }
        return value;
    }

    private String iconTypeFor(String text) {
        String value = safe(text, "").toLowerCase(Locale.ROOT);
        if (value.contains("风险")) {
            return "shield";
        }
        if (value.contains("财务") || value.contains("经营")) {
            return "finance";
        }
        if (value.contains("客户") || value.contains("负责人")) {
            return "user";
        }
        if (value.contains("报告")) {
            return "report";
        }
        return "data";
    }

    private String formatDateTime(Instant instant) {
        return DATE_TIME_FORMATTER.format(instant.atZone(ZONE_ID));
    }

    private String formatTime(Instant instant) {
        return TIME_FORMATTER.format(instant.atZone(ZONE_ID));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String shortId(String value) {
        return value == null || value.length() < 8 ? value : value.substring(0, 8).toUpperCase(Locale.ROOT);
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
