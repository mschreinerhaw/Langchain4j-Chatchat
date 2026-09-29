package com.chatchat.chat.asset;

import com.chatchat.agents.runtime.event.*;
import com.chatchat.common.runtime.analysis.asset.*;
import com.chatchat.common.runtime.analysis.evidence.*;
import com.chatchat.common.runtime.analysis.execution.*;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.analysis.plan.*;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** Independent interpretation workflow sharing the governed template-retrieval path. */
@Component
public class AssetGuidanceWorkflow implements AnalysisWorkflow {
    public static final String TEMPLATE_ID = "assetTemplateId";
    public static final List<String> STAGES = List.of("ASSET_RESOLVE", "TEMPLATE_METADATA_RETRIEVAL",
        "USAGE_EVIDENCE_CHECK", "CAPABILITY_MAPPING", "DOMAIN_SKILL_ENHANCEMENT", "GUIDANCE_RESPONSE");
    private final AssetGuidanceSource source;
    private final AssetGuidanceEnhancer enhancer;
    private final SkillExecutionScopePort scopes;
    private final ObjectProvider<AgentRunEventPublisher> events;
    private final ObjectMapper mapper;
    public AssetGuidanceWorkflow(AssetGuidanceSource source, AssetGuidanceEnhancer enhancer,
            SkillExecutionScopePort scopes, ObjectProvider<AgentRunEventPublisher> events, ObjectMapper mapper) {
        this.source = source; this.enhancer = enhancer; this.scopes = scopes; this.events = events; this.mapper = mapper;
    }
    @Override public String workflowId() { return "problem-analysis.asset-guidance.v1"; }
    @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.ASSET_GUIDANCE; }
    @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) {
        return intent.requiredCapabilities().equals(Set.of(AnalysisCapability.ASSET_GUIDANCE));
    }
    @Override public AnalysisExecutionOutcome execute(AnalysisContext input, com.chatchat.common.kernel.KernelDataScope scope) {
        if (scope == null || !scope.equals(input.kernelScope())) throw new SecurityException("Kernel scope mismatch");
        return execute(input);
    }
    @Override public AnalysisExecutionOutcome execute(AnalysisContext input) {
        if (input.kernelScope().tenantId() == null || input.kernelScope().userId() == null || input.skillId().isBlank())
            throw new SecurityException("Authenticated tenant, user and Agent are required");
        var effective = scopes.resolve(input.kernelScope().tenantId(), input.kernelScope().userId(), input.skillId(), List.of(), List.of());
        if (!effective.skillAllowed()) throw new SecurityException("Agent is not authorized");
        var context = new AnalysisContext(input.query(), input.kernelScope(), input.skillId(), List.of(), List.of(),
            effective.roles(), input.intent(), input.attributes());
        var plan = new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), STAGES.stream()
            .map(stage -> new PlanStep(stage, stage, AnalysisCapability.ASSET_GUIDANCE, true, Map.of())).toList(),
            List.of(new EvidenceRequirement("PUBLISHED_TEMPLATE", true, 1, "authorized discovery response")));
        emit(context, "ASSET_RESOLVE", "STARTED");
        emit(context, "TEMPLATE_METADATA_RETRIEVAL", "STARTED");
        AssetGuidanceSource.Result retrieved = source.retrieve(context);
        emit(context, "TEMPLATE_METADATA_RETRIEVAL", retrieved.assets().isEmpty() ? "NO_EVIDENCE" : "COMPLETED");
        List<AssetContext> candidates = retrieved.assets();
        var limitations = new ArrayList<>(retrieved.limitations());
        String requested = String.valueOf(context.attributes().getOrDefault(TEMPLATE_ID, "")).trim();
        List<AssetContext> matches = candidates.stream().filter(asset -> requested.isBlank()
            ? mentions(context.query(), asset)
            : requested.equals(asset.assetId())).toList();
        AssetContext selected = matches.size() == 1 ? matches.get(0)
            : requested.isBlank() && candidates.size() == 1 && !retrieved.hasMore() ? candidates.get(0) : null;
        String status;
        String answer;
        var recommendation = new AssetGuidanceEnhancer.Recommendation("", List.of(), "SKIPPED");
        if (selected == null) {
            status = candidates.isEmpty() ? "NO_EVIDENCE" : "NEEDS_SELECTION";
            answer = candidates.isEmpty() ? "没有检索到可验证的已授权 MCP 模板，无法确认资产用途。请确认模板已发布并授权给当前角色下的 Agent。"
                : "检索到以下候选模板，请指定模板名称或 ID；尚未执行任何模板：\n\n"
                    + candidates.stream().map(a -> "- " + a.name() + "（" + a.type() + " / " + a.assetId() + "）")
                        .collect(java.util.stream.Collectors.joining("\n"));
            emit(context, "ASSET_RESOLVE", status);
        } else {
            emit(context, "USAGE_EVIDENCE_CHECK", "MISSING");
            emit(context, "CAPABILITY_MAPPING", "TEMPLATE_DESCRIPTION_ONLY");
            // Current MCP template publication does not publish adoption/quality measurements.
            limitations.add("模板描述和参数契约不能证明实际业务使用情况、成功率、性能或质量；输出字段与指标支持需相应契约确认。");
            emit(context, "DOMAIN_SKILL_ENHANCEMENT", "STARTED");
            recommendation = enhancer.recommend(context, selected);
            emit(context, "DOMAIN_SKILL_ENHANCEMENT", recommendation.status());
            status = "PARTIAL";
            answer = "## 1. 这个资产是什么\n" + selected.name() + "（" + selected.type() + " / " + selected.assetId() + "）\n\n"
                + "## 2. 可以解决什么问题\n" + (selected.description().isBlank() ? "模板尚未提供用途说明。" : selected.description())
                + "\n\n以上是发布方声明的用途，不代表已经验证的业务效果。"
                + "\n\n## 3. 当前在哪里使用、效果如何\n暂无可验证的实际使用与质量统计；不能据此判断效果好坏。"
                + "\n\n## 4. 怎么使用\n已发布参数契约：\n```json\n" + json(selected.technicalMetadata()) + "\n```"
                + "\n\n领域建议（推断，未执行或验证）：\n" + recommendation.advice()
                + "\n\n## 5. 注意事项\n本次仅检索模板信息，没有执行 API、SQL、模板取数或数据分析。"
                + "实际使用仍需走原有授权、参数校验和受控执行流程。\n\n来源：" + selected.sourceTool()
                + "；模板 ID：" + selected.assetId();
        }
        if (!limitations.isEmpty()) answer += "\n\n信息限制：\n" + String.join("\n", limitations);
        List<AnalysisEvidence> evidence = candidates.stream().<AnalysisEvidence>map(asset -> new TemplateEvidence(
            UUID.randomUUID().toString(), json(asset), Map.of("sourceTool", asset.sourceTool(), "assetId", asset.assetId()))).toList();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("workflowFamily", RuntimeWorkflowFamily.ASSET_GUIDANCE.name());
        metadata.put("status", status); metadata.put("assets", candidates); metadata.put("hasMore", retrieved.hasMore());
        metadata.put("dataAcquisitionExecuted", false); metadata.put("templateExecutionExecuted", false);
        metadata.put("domainSkillStatus", recommendation.status()); metadata.put("appliedSkillIds", recommendation.skillIds());
        metadata.put("usageEvidenceStatus", "MISSING"); metadata.put("templateVersion", "asset-guidance.v1");
        emit(context, "GUIDANCE_RESPONSE", status);
        return new AnalysisExecutionOutcome(AnalysisExecutionOutcome.SCHEMA_VERSION, type(), plan,
            new VerificationResult(!evidence.isEmpty(), evidence, limitations),
            new EvidenceBundle(EvidenceBundle.SCHEMA_VERSION, evidence, limitations, Map.of("evidenceKind", "TEMPLATE_METADATA")),
            answer, metadata);
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("Cannot serialize template evidence", invalid); }
    }
    private boolean mentions(String query, AssetContext asset) {
        return java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(asset.assetId())
            + "(?![A-Za-z0-9_])").matcher(query).find()
            || !asset.name().equals(asset.assetId()) && asset.name().length() >= 2 && query.contains(asset.name());
    }
    private void emit(AnalysisContext context, String stage, String status) {
        String run = context.kernelScope().runId();
        if (run == null) return;
        events.orderedStream().forEach(publisher -> publisher.publish(AgentRunEvent.of(run,
            AgentRunEventType.OBSERVATION_RECORDED, "资产使用指导：" + stage,
            Map.of("workflowFamily", "ASSET_GUIDANCE", "stage", stage, "status", status))));
    }
    public record TemplateEvidence(String evidenceId, String content, Map<String, Object> attributes) implements AnalysisEvidence {
        @Override public AnalysisCapability capability() { return AnalysisCapability.ASSET_GUIDANCE; }
    }
}
