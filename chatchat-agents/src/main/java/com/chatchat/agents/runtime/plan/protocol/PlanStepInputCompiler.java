package com.chatchat.agents.runtime.plan.protocol;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the ordered compilation protocol from planner input to executable tool input. */
public final class PlanStepInputCompiler {

    public Map<String, Object> compile(InterpretationPlan.Step step,
                                       InterpretationPlanRuntime.ExecutionRequest request,
                                       Map<Integer, InterpretationPlanRuntime.StepExecution> completed,
                                       Operations operations) {
        Map<String, Object> input = new LinkedHashMap<>(step.input() == null ? Map.of() : step.input());
        InterpretationPlan plan = request == null ? null : request.plan();
        operations.applyBindings(step, plan, completed, input, request);
        if (operations.batchToolInput(input) && !operations.runtimeOwnedTemplateBatch(step, plan, completed)) {
            operations.bridgeBatchTemplateInvocations(step, request, completed, input);
            return input;
        }
        operations.establishRuntimeTemplateBinding(step, completed, input);
        operations.normalizeModelInvocationEnvelope(step, input);
        operations.normalizeWebSearchInput(step, request, input);
        operations.normalizeNewsSearchInput(step, request, input);
        operations.applyPublishedInputAdapterContract(step, request, completed, input);
        Map<String, Object> retrievalGate = operations.applyStepInputEnricher(step, request, completed, input);
        operations.normalizeDiscoveryRoutingInput(step, request, completed, input);
        operations.compileDirectToolArguments(step, request, completed, input);
        operations.hydrateExecutionContextFromCompletedAssets(step, completed, input);
        operations.normalizeSqlExecutionContext(step, input);
        boolean runtimeOwnsBatch = operations.runtimeOwnedTemplateBatch(step, plan, completed);
        Map<Integer, InterpretationPlanRuntime.StepExecution> contractContext = runtimeOwnsBatch
            ? completed : operations.resolveTemplateContractFromMcp(step, request, completed, input);
        if (!runtimeOwnsBatch) {
            operations.bridgeTemplateInvocation(step, request, contractContext, input);
            operations.validateTemplateExecutionArgumentContract(step, input);
        }
        operations.hydrateExecutionContextFromTemplateMetadata(step, contractContext, input);
        operations.hydrateSqlMetadataParametersFromMetadataSearch(step, contractContext, input);
        operations.repairTableScopedSqlTemplate(step, contractContext, input);
        operations.enforceAgentRuntimeEnvironment(step, request, input);
        if (!runtimeOwnsBatch) operations.validateRequiredExecutionTemplate(step, input, completed);
        operations.enforceCanonicalAssetContinuity(step, completed, input);
        input.remove(operations.runtimeParameterProtocolMarker());
        if (retrievalGate != null && !retrievalGate.isEmpty()) {
            input.put(operations.modelRetrievalGateKey(), retrievalGate);
        }
        if (!operations.isCrawlerTool(step.toolName())) return input;
        List<String> urls = operations.selectedUrlsFromCompletedWebSearch(completed);
        if (!urls.isEmpty() && !operations.hasNonBlankUrl(input)) input.put("url", urls.get(0));
        return input;
    }

    public interface Operations {
        void applyBindings(InterpretationPlan.Step step, InterpretationPlan plan,
                           Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input,
                           InterpretationPlanRuntime.ExecutionRequest request);
        boolean batchToolInput(Map<String, Object> input);
        boolean runtimeOwnedTemplateBatch(InterpretationPlan.Step step, InterpretationPlan plan,
                                          Map<Integer, InterpretationPlanRuntime.StepExecution> completed);
        void bridgeBatchTemplateInvocations(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request,
                                            Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void establishRuntimeTemplateBinding(InterpretationPlan.Step step,
                                             Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void normalizeModelInvocationEnvelope(InterpretationPlan.Step step, Map<String, Object> input);
        void normalizeWebSearchInput(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request, Map<String, Object> input);
        void normalizeNewsSearchInput(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request, Map<String, Object> input);
        void applyPublishedInputAdapterContract(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request,
                                                Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        Map<String, Object> applyStepInputEnricher(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request,
                                                   Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void normalizeDiscoveryRoutingInput(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request,
                                            Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void compileDirectToolArguments(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request,
                                        Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void hydrateExecutionContextFromCompletedAssets(InterpretationPlan.Step step,
                                                        Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void normalizeSqlExecutionContext(InterpretationPlan.Step step, Map<String, Object> input);
        Map<Integer, InterpretationPlanRuntime.StepExecution> resolveTemplateContractFromMcp(
            InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request,
            Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void bridgeTemplateInvocation(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request,
                                      Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void validateTemplateExecutionArgumentContract(InterpretationPlan.Step step, Map<String, Object> input);
        void hydrateExecutionContextFromTemplateMetadata(InterpretationPlan.Step step,
                                                         Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void hydrateSqlMetadataParametersFromMetadataSearch(InterpretationPlan.Step step,
                                                            Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void repairTableScopedSqlTemplate(InterpretationPlan.Step step,
                                          Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        void enforceAgentRuntimeEnvironment(InterpretationPlan.Step step, InterpretationPlanRuntime.ExecutionRequest request, Map<String, Object> input);
        void validateRequiredExecutionTemplate(InterpretationPlan.Step step, Map<String, Object> input,
                                               Map<Integer, InterpretationPlanRuntime.StepExecution> completed);
        void enforceCanonicalAssetContinuity(InterpretationPlan.Step step,
                                             Map<Integer, InterpretationPlanRuntime.StepExecution> completed, Map<String, Object> input);
        String runtimeParameterProtocolMarker();
        String modelRetrievalGateKey();
        boolean isCrawlerTool(String toolName);
        List<String> selectedUrlsFromCompletedWebSearch(Map<Integer, InterpretationPlanRuntime.StepExecution> completed);
        boolean hasNonBlankUrl(Map<String, Object> input);
    }
}
