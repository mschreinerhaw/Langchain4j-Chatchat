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
        var agent = agents.resolve(context.skillId());
        if (!agent.id().equals(context.skillId())) throw new SecurityException("Unknown Agent");
        var request = InteractionRequest.builder().tenantId(context.kernelScope().tenantId())
            .userId(context.kernelScope().userId()).skillId(agent.id()).query(context.query()).build();
        // Only publisher-declared template discovery qualifies; read-only SQL execution does not.
        Set<String> candidates = new LinkedHashSet<>(policies.resolve(request, agent).availableTools().stream()
            .filter(this::isTemplateDiscovery).toList());
        Set<String> allowed = grants.allowedIdsForAgent(ResourceAuthorizationPort.MCP_TOOL,
            request.getTenantId(), request.getUserId(), new HashSet<>(context.roles()), candidates, agent.id());
        var selected = candidates.stream().filter(allowed::contains).limit(4).toList();
        var assets = new LinkedHashMap<String, AssetContext>();
        var limitations = new ArrayList<String>();
        Object requestedTemplate = context.attributes().get(AssetGuidanceWorkflow.TEMPLATE_ID);
        String recallIntent = requestedTemplate instanceof String id && !id.isBlank()
            ? id + " " + context.query() : context.query();
        boolean more = candidates.stream().filter(allowed::contains).count() > selected.size();
        if (selected.isEmpty()) limitations.add("没有可用的已授权 MCP 模板检索能力；不会执行取数工具作为替代。");
        for (String tool : selected) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (!isTemplateDiscovery(tool)) { limitations.add("模板检索发布状态已变化。"); continue; }
            try {
                var attributes = new LinkedHashMap<String, Object>();
                attributes.put("authorizationAgentId", agent.id());
                attributes.put("workflowFamily", "ASSET_GUIDANCE");
                attributes.put("toolRegistryRevisions", Map.of(tool, registry.getToolRevision(tool)));
                if (context.kernelScope().runId() != null) attributes.put("agentRunId", context.kernelScope().runId());
                var execution = tools.execute(ToolRuntimeRequest.builder().toolName(tool).runtimeMode("asset_guidance")
                    .tenantId(request.getTenantId()).userId(request.getUserId()).requestId(context.kernelScope().requestId())
                    .conversationId(context.kernelScope().conversationId()).allowedTools(List.of(tool))
                    .attributes(attributes)
                    .toolInput(ToolInput.builder().requestId(context.kernelScope().requestId()).userId(request.getUserId())
                        .parameters(Map.of("intentZh", recallIntent, "limit", 20)).build()).build());
                if (execution == null || execution.output() == null || !execution.output().isSuccess()) {
                    limitations.add("模板检索失败或被拒绝：" + tool); continue;
                }
                String raw = mapper.writeValueAsString(execution.output().getData());
                if (raw.length() > 262144) { limitations.add("模板响应超过读取上限：" + tool); more = true; continue; }
                JsonNode root = mapper.readTree(raw);
                if (!root.has("templates") && root.path("data").isObject()) root = root.path("data");
                if (!root.path("success").asBoolean(false) || !root.path("templates").isArray()
                    || root.path("rawExecutionSpecReturned").asBoolean(false)) {
                    limitations.add("模板响应格式无法验证：" + tool); continue;
                }
                more |= root.path("hasMore").asBoolean(false) || root.path("pagination").path("hasMore").asBoolean(false);
                if (root.has("bindingComplete") && !root.path("bindingComplete").asBoolean())
                    limitations.add("部分模板已失效或当前无权访问。");
                for (JsonNode item : root.path("templates")) {
                    if (assets.size() >= 20) { more = true; break; }
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
        if (more) limitations.add("当前仅为有界候选页，并非全部资产；可补充模板名称缩小检索范围。");
        return new Result(List.copyOf(assets.values()), limitations, more);
    }
    private boolean isTemplateDiscovery(String name) {
        ToolMetadata meta = registry.getToolMetadata(name);
        return meta != null && meta.isUserVisible() && meta.isAgentCompatible()
            && "active".equalsIgnoreCase(meta.getPublicationStatus())
            && "read".equalsIgnoreCase(meta.getOperationType())
            && ToolWorkflowContract.resolveRole(name, meta) == ToolWorkflowRole.TEMPLATE_DISCOVERY;
    }
    private static String text(JsonNode node, String key, int max) {
        String value = node.path(key).asText("");
        return value.substring(0, Math.min(value.length(), max));
    }
}
