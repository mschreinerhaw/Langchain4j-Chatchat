package com.chatchat.chat.asset;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AssetGuidanceInteractionBridge {
    private final AnalysisRuntimePort runtime;
    public AssetGuidanceInteractionBridge(AnalysisRuntimePort runtime) { this.runtime = runtime; }
    public InteractionResponse execute(InteractionRequest request, InteractionContext context, SkillDefinition agent) {
        Object rawRun = request.getToolInput() == null ? null : request.getToolInput().get("__agentRunId");
        String run = rawRun instanceof String value ? value : null;
        var attrs = new LinkedHashMap<String, Object>();
        if (context.problemAnalysisPlan() != null)
            attrs.put(com.chatchat.common.runtime.capability.ProblemAnalysisPlan.METADATA_KEY, context.problemAnalysisPlan());
        Object templateId = request.getToolInput() == null ? null : request.getToolInput().get(AssetGuidanceWorkflow.TEMPLATE_ID);
        if (templateId instanceof String value && value.length() <= 256) attrs.put(AssetGuidanceWorkflow.TEMPLATE_ID, value);
        var kernel = new KernelDataScope(request.getTenantId(), request.getUserId(), context.requestId(),
            context.conversationId(), run, null, Map.of());
        var intent = new AnalysisIntent("ASSET_GUIDANCE", List.of(), Set.of(AnalysisCapability.ASSET_GUIDANCE), "UNSPECIFIED", true);
        var result = runtime.analyze(new AnalysisContext(request.getQuery(), kernel, agent.id(), List.of(), List.of(), List.of(), intent, attrs));
        // Preserve Runtime's evidence decision: an explanatory answer is not successful analysis.
        String publicStatus = String.valueOf(result.metadata().getOrDefault("runtimePublicStatus", "NO_PRESENTABLE_RESULT"));
        if (!Set.of("PARTIAL_SUCCESS", "NO_PRESENTABLE_RESULT").contains(publicStatus)) publicStatus = "NO_PRESENTABLE_RESULT";
        boolean usable = result.verification() != null && result.verification().accepted() && !result.evidenceBundle().evidence().isEmpty();
        var outcome = new com.chatchat.common.runtime.capability.WorkflowOutcome(
            usable ? com.chatchat.common.runtime.capability.WorkflowOutcome.Type.PARTIAL_RESULT
                : com.chatchat.common.runtime.capability.WorkflowOutcome.Type.INSUFFICIENT_EVIDENCE,
            String.valueOf(result.metadata().getOrDefault("runtimeGuidanceDecision", "INSUFFICIENT_EVIDENCE")),
            usable ? List.of() : List.of("asset_metadata_read"), List.of("asset_usage_read"), usable);
        return InteractionResponse.builder().conversationId(context.conversationId()).requestId(context.requestId())
            .mode("agent_chat").answer(result.synthesis()).metadata(Map.of("workflowFamily", "ASSET_GUIDANCE", "assetGuidance", result,
                "handler", "AssetGuidanceInteractionBridge", "agent", Map.of("publicStatus", publicStatus),
                com.chatchat.common.runtime.capability.WorkflowOutcome.METADATA_KEY, outcome))
            .timestamp(System.currentTimeMillis()).build();
    }
}
