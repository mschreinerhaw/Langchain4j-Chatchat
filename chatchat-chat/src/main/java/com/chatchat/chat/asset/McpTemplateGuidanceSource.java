package com.chatchat.chat.asset;

import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.service.AgentToolPolicyResolver;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.runtime.analysis.asset.*;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Reuses published template discovery and its existing parent delegation; never executes a template. */
@Component
@lombok.extern.slf4j.Slf4j
public class McpTemplateGuidanceSource implements AssetGuidanceSource {
    private final SkillCatalogService agents;
    private final AgentToolPolicyResolver policies;
    private final ResourceAuthorizationPort grants;
    private final ToolRegistry registry;
    private final ToolRuntimeService tools;
    private final ObjectMapper mapper;
    public McpTemplateGuidanceSource(SkillCatalogService agents, AgentToolPolicyResolver policies,
            ResourceAuthorizationPort grants, ToolRegistry registry, ToolRuntimeService tools, ObjectMapper mapper) {
        this.agents = agents; this.policies = policies; this.grants = grants;
        this.registry = registry; this.tools = tools; this.mapper = mapper;
    }
    @Override public Result retrieve(AnalysisContext context) {
        return retrieve(context, ToolWorkflowRole.TEMPLATE_DISCOVERY);
    }
    @Override public Result acquireMetadata(AnalysisContext context, GuidanceDataRequestPlan plan) {
        return retrieve(context.withAttribute("guidanceDataRequestPlan", plan), ToolWorkflowRole.ASSET_DISCOVERY);
    }
    private Result retrieve(AnalysisContext context, ToolWorkflowRole role) {
        var agent = agents.resolve(context.skillId());
        if (!agent.id().equals(context.skillId())) throw new SecurityException("Unknown Agent");
        String selectionQuery = context.query();
        if (context.attributes().get("guidanceDataRequestPlan") instanceof GuidanceDataRequestPlan plan) {
            selectionQuery += "\nMetadata-only " + role.name() + "; assetType=" + plan.assetType()
                + "; intent=" + plan.intent() + "; requirements=" + String.join(",", plan.requirements());
        }
        var request = InteractionRequest.builder().tenantId(context.kernelScope().tenantId())
            .userId(context.kernelScope().userId()).skillId(agent.id()).query(selectionQuery).build();
        // Only publisher-declared template discovery qualifies; read-only SQL execution does not.
        var policy = role == ToolWorkflowRole.TEMPLATE_DISCOVERY ? policies.resolveTemplateDiscovery(request, agent)
            : assetPolicy(request, agent);
        Set<String> candidates = new LinkedHashSet<>(policy.availableTools().stream()
            .filter(name -> isDiscovery(name, role)).toList());
        Set<String> allowed = grants.allowedIdsForAgent(ResourceAuthorizationPort.MCP_TOOL,
            request.getTenantId(), request.getUserId(), new HashSet<>(context.roles()), candidates, agent.id());
        var authorized = candidates.stream().filter(allowed::contains).toList();
        var selected = authorized.stream().limit(4).toList();
        var assets = new LinkedHashMap<String, AssetContext>();
        var limitations = new ArrayList<String>();
        Object requestedTemplate = context.attributes().get(AssetGuidanceWorkflow.TEMPLATE_ID);
        String recallIntent = requestedTemplate instanceof String id && !id.isBlank()
            ? id + " " + context.query() : context.query();
        boolean more = authorized.size() > selected.size();
        if (selected.isEmpty()) {
            String reason = policy.boundToolCount() == 0 ? "NO_BOUND_TOOLS"
                : policy.eligibleToolCount() == 0 ? "NO_DISCOVERY_CONTRACT"
                : candidates.isEmpty() ? "MCP_CATALOG_OR_AUTHORIZATION_REJECTED" : "RESOURCE_AUTHORIZATION_REJECTED";
            String detail = switch (reason) {
                case "NO_BOUND_TOOLS" -> "通用工具策略未选出候选，请检查 Agent 绑定、Top-K 召回及授权状态。";
                case "NO_DISCOVERY_CONTRACT" -> "本轮通用工具候选中没有已启用、只读且声明 " + role.name() + " 契约的元数据工具。";
                case "MCP_CATALOG_OR_AUTHORIZATION_REJECTED" -> "模板检索工具未通过 MCP 目录状态或授权校验，请检查工具是否已入库上线、MCP 工具权限及角色资源授权。";
                default -> "模板检索工具未通过当前用户在该 Agent 下的资源授权校验。";
            };
            limitations.add("[" + reason + "] " + detail + "不会执行取数工具作为替代。");
            log.info("Asset guidance discovery unavailable. requestId={} agentId={} reason={} bound={} eligible={} candidates={} authorized={}",
                context.kernelScope().requestId(), agent.id(), reason, policy.boundToolCount(),
                policy.eligibleToolCount(), candidates.size(), selected.size());
        }
        for (String tool : selected) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (!isDiscovery(tool, role)) { limitations.add("元数据检索发布状态已变化。"); continue; }
            try {
                var attributes = new LinkedHashMap<String, Object>();
                attributes.put("authorizationAgentId", agent.id());
                attributes.put("workflowFamily", "ASSET_GUIDANCE");
                if (context.attributes().get("guidanceDataRequestPlan") instanceof GuidanceDataRequestPlan plan)
                    attributes.put("guidanceDataRequestPlan", plan);
                attributes.put("toolRegistryRevisions", Map.of(tool, registry.getToolRevision(tool)));
                if (context.kernelScope().runId() != null) attributes.put("agentRunId", context.kernelScope().runId());
                var execution = tools.execute(ToolRuntimeRequest.builder().toolName(tool).runtimeMode("asset_guidance")
                    .tenantId(request.getTenantId()).userId(request.getUserId()).requestId(context.kernelScope().requestId())
                    .conversationId(context.kernelScope().conversationId()).allowedTools(List.of(tool))
                    .attributes(attributes)
                    .toolInput(ToolInput.builder().requestId(context.kernelScope().requestId()).userId(request.getUserId())
                        .parameters(role == ToolWorkflowRole.TEMPLATE_DISCOVERY
                            ? Map.of("intentZh", recallIntent, "limit", 20)
                            : Map.of("filters", Map.of("intentZh", recallIntent), "limit", 20)).build()).build());
                if (execution == null || execution.output() == null || !execution.output().isSuccess()) {
                    limitations.add("模板检索失败或被拒绝：" + tool); continue;
                }
                String raw = mapper.writeValueAsString(execution.output().getData());
                if (raw.length() > 262144) { limitations.add("模板响应超过读取上限：" + tool); more = true; continue; }
                JsonNode root = mapper.readTree(raw);
                String collection = role == ToolWorkflowRole.TEMPLATE_DISCOVERY ? "templates" : "assets";
                if (!root.has(collection) && root.path("data").isObject()) root = root.path("data");
                if (!root.path("success").asBoolean(false) || !root.path(collection).isArray()
                    || root.path("rawExecutionSpecReturned").asBoolean(false)) {
                    limitations.add("模板响应格式无法验证：" + tool); continue;
                }
                more |= root.path("hasMore").asBoolean(false) || root.path("pagination").path("hasMore").asBoolean(false);
                if (root.has("bindingComplete") && !root.path("bindingComplete").asBoolean())
                    limitations.add("部分模板已失效或当前无权访问。");
                for (JsonNode item : root.path(collection)) {
                    if (assets.size() >= 20) { more = true; break; }
                    if (role == ToolWorkflowRole.ASSET_DISCOVERY) {
                        JsonNode identity = item.path("asset");
                        String assetId = text(identity, "id", 256);
                        if (assetId.isBlank()) assetId = text(identity, "name", 256);
                        if (assetId.isBlank()) continue;
                        JsonNode analysis = item.path("analysisIdentity");
                        String display = text(analysis, "displayName", 300);
                        if (display.isBlank()) display = text(identity, "name", 300);
                        var asset = new AssetContext(assetId, text(item, "assetType", 80), display,
                            text(analysis, "toolDescription", 6000), Map.of("evidenceKind", "ASSET_METADATA"),
                            Map.of(), Map.of(), tool, Map.of());
                        assets.put(tool + ":" + assetId, asset);
                        continue;
                    }
                    String id = text(item, "templateId", 256);
                    if (id.isBlank()) continue;
                    // Fixed, consumer-facing fields only. Raw SQL, commands, URLs and credentials are excluded.
                    Map<String, Object> technical = new LinkedHashMap<>();
                    if (item.path("parameterSchema").isObject())
                        technical.put("parameterSchema", mapper.convertValue(item.path("parameterSchema"), Map.class));
                    technical.put("businessCategory", text(item, "businessCategoryName", 200));
                    Map<String, Object> provenance = new LinkedHashMap<>();
                    for (String field : List.of("sourceRef", "dataVersion", "asOf")) {
                        String value = text(root.path("provenance"), field, 500);
                        if (!value.isBlank()) provenance.put(field, value);
                    }
                    var asset = new AssetContext(id, text(item, "assetType", 80), text(item, "title", 300),
                        text(item, "description", 6000), technical, Map.of(), Map.of(), tool, provenance);
                    assets.put(tool + ":" + asset.type() + ":" + id, asset);
                }
            } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                limitations.add("模板检索暂不可用：" + tool);
            }
        }
        if (!selected.isEmpty() && assets.isEmpty() && limitations.isEmpty())
            limitations.add("[NO_TEMPLATE_MATCH] 已执行模板元数据检索，但本批未返回匹配模板，可补充模板名称或 ID。");
        if (more) limitations.add("当前仅为有界候选页，并非全部资产；可补充模板名称缩小检索范围。");
        return new Result(List.copyOf(assets.values()), limitations, more);
    }
    private AgentToolPolicyResolver.DiscoveryToolPolicy assetPolicy(InteractionRequest request,
            com.chatchat.chat.skills.model.SkillDefinition agent) {
        var selected = policies.resolve(request, agent).availableTools();
        var eligible = selected.stream().filter(name -> isDiscovery(name, ToolWorkflowRole.ASSET_DISCOVERY)).toList();
        return new AgentToolPolicyResolver.DiscoveryToolPolicy(eligible, selected.size(), eligible.size());
    }
    private boolean isDiscovery(String name, ToolWorkflowRole role) {
        ToolMetadata meta = registry.getToolMetadata(name);
        return meta != null && meta.isUserVisible() && meta.isAgentCompatible()
            && "active".equalsIgnoreCase(meta.getPublicationStatus())
            && "read".equalsIgnoreCase(meta.getOperationType())
            && ToolWorkflowContract.resolveRole(name, meta) == role;
    }
    private static String text(JsonNode node, String key, int max) {
        String value = node.path(key).asText("");
        return value.substring(0, Math.min(value.length(), max));
    }
}
