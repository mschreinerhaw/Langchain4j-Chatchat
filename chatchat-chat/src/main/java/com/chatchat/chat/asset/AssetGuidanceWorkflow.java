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
public class AssetGuidanceWorkflow implements AssetGuidancePlanningWorkflow {
    public static final String TEMPLATE_ID = "assetTemplateId";
    public static final List<String> STAGES = List.of("ASSET_GUIDANCE_START", "ASSET_RESOLVE", "ASSET_CONTEXT_REQUIRED",
        "TEMPLATE_RESOLVE", "DATA_ACQUISITION_REQUIRED", "DATA_BUNDLE_READY", "DOMAIN_SKILL_ENRICHMENT",
        "GUIDANCE_SYNTHESIS", "GUIDANCE_READY");
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
        var context = authorizedContext(input);
        var plan = new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), STAGES.stream()
            .map(stage -> new PlanStep(stage, stage, AnalysisCapability.ASSET_GUIDANCE, true, Map.of())).toList(),
            List.of(new EvidenceRequirement("ASSET_METADATA", true, 1, "authorized metadata discovery response")));
        emit(context, "ASSET_GUIDANCE_START", "STARTED");
        emit(context, "ASSET_RESOLVE", "SEARCH_REQUIRED");
        emit(context, "ASSET_CONTEXT_REQUIRED", "PLANNED");
        var request = dataPlan(context, null);
        return new AnalysisExecutionOutcome(null, type(), plan, new VerificationResult(false, List.of(), List.of()),
            EvidenceBundle.empty(null), "", Map.of("guidanceState", "ASSET_CONTEXT_REQUIRED", "dataRequestPlan", request));
    }
    private AnalysisContext authorizedContext(AnalysisContext input) {
        if (input.kernelScope().tenantId() == null || input.kernelScope().userId() == null || input.skillId().isBlank())
            throw new SecurityException("Authenticated tenant, user and Agent are required");
        var effective = scopes.resolve(input.kernelScope().tenantId(), input.kernelScope().userId(), input.skillId(), List.of(), List.of());
        if (!effective.skillAllowed()) throw new SecurityException("Agent is not authorized");
        return new AnalysisContext(input.query(), input.kernelScope(), input.skillId(), List.of(), List.of(),
            effective.roles(), input.intent(), input.attributes());
    }
    @Override public AssetGuidanceSource.Result resolveTemplate(AnalysisContext input, AnalysisExecutionOutcome understanding) {
        var context = authorizedContext(input);
        emit(context, "TEMPLATE_RESOLVE", "STARTED");
        return source.retrieve(context.withAttribute("guidanceDataRequestPlan", understanding.metadata().get("dataRequestPlan")));
    }
    @Override public AnalysisExecutionOutcome requestData(AnalysisContext input, AnalysisExecutionOutcome understanding,
            AssetGuidanceSource.Result templates) {
        var context = authorizedContext(input);
        AssetContext template = select(context, templates);
        var request = dataPlan(context, template);
        emit(context, "TEMPLATE_RESOLVE", template == null ? "DEFAULT_REQUIREMENTS" : "TEMPLATE_REQUIREMENTS");
        return new AnalysisExecutionOutcome(null, type(), understanding.plan(), understanding.verification(),
            understanding.evidenceBundle(), "", Map.of("guidanceState", "DATA_ACQUISITION_REQUIRED", "dataRequestPlan", request));
    }
    @Override public AssetGuidanceSource.Result acquireData(AnalysisContext input, AnalysisExecutionOutcome request,
            AssetGuidanceSource.Result templates) {
        var context = authorizedContext(input);
        emit(context, "DATA_ACQUISITION_REQUIRED", "STARTED");
        // Reuse already acquired metadata. Never re-execute a template or repeat a lookup.
        if (!templates.assets().isEmpty()) return templates;
        var plan = (GuidanceDataRequestPlan) request.metadata().get("dataRequestPlan");
        var result = source.acquireMetadata(context, plan);
        var limitations = new ArrayList<>(templates.limitations());
        limitations.addAll(result.limitations());
        return new AssetGuidanceSource.Result(result.assets(), limitations, result.hasMore());
    }
    private GuidanceDataRequestPlan dataPlan(AnalysisContext context, AssetContext template) {
        String query = context.query();
        String assetType = template != null ? template.type() : query.toLowerCase(Locale.ROOT).contains("api") ? "api_service"
            : query.contains("表") || query.toLowerCase(Locale.ROOT).contains("table") ? "table"
            : query.contains("指标") ? "indicator" : "UNSPECIFIED";
        List<String> requirements = new ArrayList<>(List.of("asset_identity", "declared_purpose", "parameter_contract",
            "output_contract", "usage_evidence_if_published"));
        if ("table".equals(assetType)) requirements.addAll(List.of("column_definitions", "data_granularity"));
        if ("indicator".equals(assetType)) requirements.addAll(List.of("metric_definition", "unit_and_granularity"));
        if (template != null) requirements.addAll(template.technicalMetadata().keySet());
        String domain = template == null ? "UNSPECIFIED"
            : String.valueOf(template.technicalMetadata().getOrDefault("businessCategory", "UNSPECIFIED"));
        return new GuidanceDataRequestPlan(assetType, domain, "ASSET_UNDERSTANDING_AND_USAGE",
            query, template == null ? "" : template.assetId(), requirements, template == null ? "DEFAULT" : "TEMPLATE");
    }
    private AssetContext select(AnalysisContext context, AssetGuidanceSource.Result retrieved) {
        String requested = String.valueOf(context.attributes().getOrDefault(TEMPLATE_ID, "")).trim();
        var matches = retrieved.assets().stream().filter(asset -> requested.isBlank()
            ? mentions(context.query(), asset) : requested.equals(asset.assetId())).toList();
        return matches.size() == 1 ? matches.get(0)
            : requested.isBlank() && retrieved.assets().size() == 1 && !retrieved.hasMore() ? retrieved.assets().get(0) : null;
    }
    @Override public AnalysisExecutionOutcome synthesize(AnalysisContext input, AnalysisExecutionOutcome request,
            AssetGuidanceSource.Result retrieved) {
        var context = authorizedContext(input);
        var plan = request.plan();
        emit(context, "DATA_BUNDLE_READY", retrieved.assets().isEmpty() ? "INSUFFICIENT_EVIDENCE" : "READY");
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
            answer = candidates.isEmpty() ? "已按资产理解计划检索模板和资产元数据，但仍无法确认目标资产。请提供资产名称、类型或 ID。"
                : "检索到以下候选资产，请指定名称或 ID；尚未执行任何业务取数：\n\n"
                    + candidates.stream().map(a -> "- " + a.name() + "（" + a.type() + " / " + a.assetId() + "）")
                        .collect(java.util.stream.Collectors.joining("\n"));
            emit(context, "ASSET_RESOLVE", status);
        } else {
            emit(context, "USAGE_EVIDENCE_CHECK", "MISSING");
            emit(context, "CAPABILITY_MAPPING", "TEMPLATE_DESCRIPTION_ONLY");
            // Current MCP template publication does not publish adoption/quality measurements.
            limitations.add("模板描述和参数契约不能证明实际业务使用情况、成功率、性能或质量；输出字段与指标支持需相应契约确认。");
            emit(context, "DOMAIN_SKILL_ENRICHMENT", "STARTED");
            recommendation = enhancer.recommend(context, selected);
            emit(context, "DOMAIN_SKILL_ENRICHMENT", recommendation.status());
            emit(context, "GUIDANCE_SYNTHESIS", "STARTED");
            status = "PARTIAL";
            answer = "## 1. 这个资产是什么\n" + selected.name() + "（" + selected.type() + " / " + selected.assetId() + "）\n\n"
                + "## 2. 可以解决什么问题\n" + (selected.description().isBlank() ? "发布元数据尚未提供用途说明。" : selected.description())
                + "\n\n以上是发布方声明的用途，不代表已经验证的业务效果。"
                + "\n\n## 3. 当前在哪里使用、效果如何\n暂无可验证的实际使用与质量统计；不能据此判断效果好坏。"
                + "\n\n## 4. 怎么使用\n已获取的发布元数据（缺失契约不作推断）：\n```json\n" + json(selected.technicalMetadata()) + "\n```"
                + "\n\n领域建议（推断，未执行或验证）：\n" + recommendation.advice()
                + "\n\n## 5. 注意事项\n本次仅检索模板和资产元数据，没有执行 API、SQL、模板取数或数据分析。"
                + "实际使用仍需走原有授权、参数校验和受控执行流程。\n\n来源：" + selected.sourceTool()
                + "；资产/模板 ID：" + selected.assetId();
        }
        if (!limitations.isEmpty()) answer += "\n\n信息限制：\n" + String.join("\n", limitations);
        List<AnalysisEvidence> evidence = candidates.stream().<AnalysisEvidence>map(asset -> new TemplateEvidence(
            UUID.randomUUID().toString(), json(asset), Map.of("sourceTool", asset.sourceTool(), "assetId", asset.assetId()))).toList();
        var metadata = new LinkedHashMap<String, Object>(request.metadata());
        metadata.put("workflowFamily", RuntimeWorkflowFamily.ASSET_GUIDANCE.name());
        metadata.put("status", status); metadata.put("assets", candidates); metadata.put("hasMore", retrieved.hasMore());
        metadata.put("dataAcquisitionExecuted", false); metadata.put("templateExecutionExecuted", false);
        metadata.put("metadataAcquisitionAttempted", true);
        metadata.put("domainSkillStatus", recommendation.status()); metadata.put("appliedSkillIds", recommendation.skillIds());
        metadata.put("usageEvidenceStatus", "MISSING"); metadata.put("templateVersion", "asset-guidance.v1");
        String guidanceState = selected != null ? "GUIDANCE_READY"
            : candidates.isEmpty() ? "GUIDANCE_CONTEXT_REQUIRED" : "GUIDANCE_NEEDS_SELECTION";
        metadata.put("guidanceState", guidanceState);
        emit(context, guidanceState, status);
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
