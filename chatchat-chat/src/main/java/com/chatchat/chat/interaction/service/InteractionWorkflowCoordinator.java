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
        if (request.getToolInput() != null && request.getToolInput().get("__userAssetIds") instanceof java.util.List<?> ids && !ids.isEmpty()) {
            var files = execution.call("FILE_REFERENCES_PREPARING", () -> direct.describeFiles(request));
            var purposes = AgentToolPolicyResolver.hasSelectedCapabilities(request, skill)
                ? policies.planningSnapshot(request, skill).toolPurposes() : java.util.List.<java.util.Map<String,Object>>of();
            // A supplied/client plan cannot authorize a file reference or waive a business dependency.
            var plan = planner == null ? ProblemAnalysisPlan.unavailable()
                : execution.call("PROBLEM_ANALYSIS_PLAN", () -> planner.analyzeFiles(request, context, skill, purposes, files));
            if (!ProblemAnalysisPlanner.executable(plan) || plan.fileAnalysis() == null)
                return execution.complete(ProblemAnalysisPlanner.blockedResponse(plan),
                    WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.PROBLEM_ANALYSIS, "USER_FILE_ANALYSIS"));
            var planned = context.toBuilder().problemAnalysisPlan(plan)
                .taskType(ProblemAnalysisPlan.TaskType.DATA_ANALYSIS).build();
            var response = plan.fileAnalysis().toolDataDependency() == ProblemAnalysisPlan.ToolDataDependency.NONE
                ? execution.call("CLINE_SUBMITTING", () -> direct.execute(request, planned, skill))
                : execution.call("GOVERNED_RUNTIME", () -> governed.execute(planned, plan));
            return execution.complete(ProblemAnalysisPlanner.attach(response, plan),
                WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.PROBLEM_ANALYSIS, "USER_FILE_ANALYSIS"));
        }
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
            case GOVERNED_RUNTIME -> {
                // Maintained DAGs keep their planning owner, but still need model-based analysis policy.
                // Non-Spring compatibility constructors have no classifier; production injection is required.
                if (planner == null) yield execution.call("GOVERNED_RUNTIME", () -> governed.execute(context, null));
                var type = execution.call("TASK_CLASSIFICATION", () -> planner.classify(request, context, skill, entry.toolPurposes()));
                if (type == null) yield ProblemAnalysisPlanner.blockedResponse(ProblemAnalysisPlan.unavailable());
                var classified = context.toBuilder().taskType(type).build();
                var result = type == ProblemAnalysisPlan.TaskType.QA
                    ? executeWorkflow(execution, RuntimeWorkflowFamily.DIRECT_ANSWER, request, classified, skill, null, governed)
                    : execution.call("GOVERNED_RUNTIME", () -> governed.execute(classified, null));
                var metadata = new java.util.LinkedHashMap<String,Object>(result.getMetadata() == null ? java.util.Map.of() : result.getMetadata());
                metadata.put("taskType", type.name());
                result.setMetadata(metadata);
                yield result;
            }
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
