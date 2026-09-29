package com.chatchat.chat.interaction.service;

import com.chatchat.chat.asset.AssetGuidanceInteractionBridge;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily;
import com.chatchat.common.runtime.capability.*;
import java.util.ArrayList;
import java.util.Locale;

/** One planning owner per invocation; every branch joins before common outcome projection. */
public final class InteractionWorkflowCoordinator {
    @FunctionalInterface
    public interface GovernedExecution {
        InteractionResponse execute(InteractionContext context, ProblemAnalysisPlan understanding);
    }

    private final AgentToolPolicyResolver policies;
    private final ProblemAnalysisPlanner planner;
    private final DirectAnswerWorkflow direct;
    private final AssetGuidanceInteractionBridge assets;

    public InteractionWorkflowCoordinator(AgentToolPolicyResolver policies, ProblemAnalysisPlanner planner,
                                          DirectAnswerWorkflow direct, AssetGuidanceInteractionBridge assets) {
        this.policies = policies; this.planner = planner; this.direct = direct; this.assets = assets;
    }

    public InteractionResponse execute(InteractionRequest request, InteractionContext context, SkillDefinition skill,
                                       GovernedExecution governed) {
        var execution = new InteractionExecution(request);
        WorkflowEntryPlan entry = execution.call("SELECT_PLANNING_OWNER", () -> {
            if (context.problemAnalysisPlan() != null)
                return WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.PROVIDED_PLAN, "EXISTING_PROBLEM_PLAN");
            if (!AgentToolPolicyResolver.hasSelectedCapabilities(request, skill))
                return WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.DIRECT_ANSWER, "NO_SELECTED_CAPABILITIES");
            return policies.planningSnapshot(request, skill);
        });
        InteractionResponse response = switch (entry.owner()) {
            case DIRECT_ANSWER -> executeWorkflow(execution, RuntimeWorkflowFamily.DIRECT_ANSWER,
                request, context, skill, null, governed);
            case GOVERNED_RUNTIME -> execution.call("GOVERNED_RUNTIME", () -> governed.execute(context, null));
            case PROBLEM_ANALYSIS, PROVIDED_PLAN -> {
                ProblemAnalysisPlan understanding = context.problemAnalysisPlan() != null ? context.problemAnalysisPlan()
                    : execution.call("PROBLEM_ANALYSIS_PLAN", () -> planner == null ? ProblemAnalysisPlan.unavailable()
                        : planner.analyze(request, context, skill, entry.toolPurposes()));
                if (!ProblemAnalysisPlanner.executable(understanding)) yield ProblemAnalysisPlanner.blockedResponse(understanding);
                RuntimeWorkflowFamily family = execution.call("SELECT_WORKFLOW", () -> new CapabilityWorkflowRouter().route(understanding));
                InteractionContext planned = context.toBuilder().problemAnalysisPlan(understanding).build();
                yield ProblemAnalysisPlanner.attach(executeWorkflow(execution, family, request, planned, skill, understanding, governed), understanding);
            }
            case ROLE_CONVERSATION -> throw new IllegalStateException("Role conversation has its own context adapter");
        };
        return execution.complete(response, entry);
    }

    private InteractionResponse executeWorkflow(InteractionExecution execution, RuntimeWorkflowFamily family,
            InteractionRequest request, InteractionContext context, SkillDefinition skill,
            ProblemAnalysisPlan understanding, GovernedExecution governed) {
        CapabilityWorkflowPlan plan = CapabilityWorkflowPlan.forFamily(family);
        var providers = new ArrayList<CapabilityWorkflowRuntime.Provider>();
        if (family == RuntimeWorkflowFamily.DIRECT_ANSWER) {
            if (direct != null) providers.add(new CapabilityWorkflowRuntime.Provider("direct-answer",
                CapabilityWorkflowRuntime.ProviderKind.NATIVE_RUNTIME, plan.requiredCapabilities(), () -> direct.execute(request, context, skill)));
        } else if (family == RuntimeWorkflowFamily.ASSET_GUIDANCE) {
            if (assets != null) providers.add(new CapabilityWorkflowRuntime.Provider("asset-metadata-workflow",
                CapabilityWorkflowRuntime.ProviderKind.MCP_WORKFLOW, plan.requiredCapabilities(), () -> assets.execute(request, context, skill)));
        } else {
            providers.add(new CapabilityWorkflowRuntime.Provider("governed-" + family.name().toLowerCase(Locale.ROOT),
                family == RuntimeWorkflowFamily.DOCUMENT ? CapabilityWorkflowRuntime.ProviderKind.DOCUMENT_RETRIEVAL
                    : CapabilityWorkflowRuntime.ProviderKind.NATIVE_RUNTIME,
                plan.requiredCapabilities(), () -> governed.execute(context, understanding)));
        }
        return execution.call("EXECUTE_WORKFLOW", () -> new CapabilityWorkflowRuntime().execute(plan, providers));
    }
}
