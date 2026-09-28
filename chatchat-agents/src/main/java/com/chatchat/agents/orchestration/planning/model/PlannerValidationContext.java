package com.chatchat.agents.orchestration.planning.model;

import com.chatchat.agents.orchestration.planning.validation.AgentPlanBudgetPolicy;

import java.util.List;
import java.util.Map;

/** Runtime-owned constraints used to validate and score a proposed plan. */
public record PlannerValidationContext(
    List<String> mandatoryTools,
    boolean requireToolBeforeFinal,
    boolean requireDocumentWebVerification,
    String documentSearchTool,
    String verificationWebSearchTool,
    List<String> availableTools,
    String query,
    Map<String, Object> experiencePrior,
    AgentPlanBudgetPolicy.BudgetCaps budgetCaps,
    Object authoritativeWorkflowDag,
    Object agentWorkflow,
    List<String> optionalTools
) {
    public PlannerValidationContext {
        mandatoryTools = mandatoryTools == null ? List.of() : List.copyOf(mandatoryTools);
        availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        optionalTools = optionalTools == null ? List.of() : List.copyOf(optionalTools);
        experiencePrior = experiencePrior == null ? Map.of() : Map.copyOf(experiencePrior);
    }

    public PlannerValidationContext(List<String> mandatoryTools,
                                    boolean requireToolBeforeFinal,
                                    boolean requireDocumentWebVerification,
                                    String documentSearchTool,
                                    String verificationWebSearchTool,
                                    List<String> availableTools,
                                    String query,
                                    Map<String, Object> experiencePrior,
                                    AgentPlanBudgetPolicy.BudgetCaps budgetCaps,
                                    Object authoritativeWorkflowDag,
                                    Object agentWorkflow) {
        this(mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, availableTools, query, experiencePrior,
            budgetCaps, authoritativeWorkflowDag, agentWorkflow, List.of());
    }

    public PlannerValidationContext(List<String> mandatoryTools,
                                    boolean requireToolBeforeFinal,
                                    boolean requireDocumentWebVerification,
                                    String documentSearchTool,
                                    String verificationWebSearchTool,
                                    List<String> availableTools,
                                    String query,
                                    Map<String, Object> experiencePrior,
                                    AgentPlanBudgetPolicy.BudgetCaps budgetCaps,
                                    Object authoritativeWorkflowDag) {
        this(mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, availableTools, query, experiencePrior,
            budgetCaps, authoritativeWorkflowDag, null, List.of());
    }

    public PlannerValidationContext(List<String> mandatoryTools,
                                    boolean requireToolBeforeFinal,
                                    boolean requireDocumentWebVerification,
                                    String documentSearchTool,
                                    String verificationWebSearchTool,
                                    List<String> availableTools,
                                    String query,
                                    Map<String, Object> experiencePrior,
                                    AgentPlanBudgetPolicy.BudgetCaps budgetCaps) {
        this(mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, availableTools, query, experiencePrior,
            budgetCaps, null, null, List.of());
    }

    public PlannerValidationContext(List<String> mandatoryTools,
                                    boolean requireToolBeforeFinal,
                                    boolean requireDocumentWebVerification,
                                    String documentSearchTool,
                                    String verificationWebSearchTool,
                                    List<String> availableTools,
                                    String query,
                                    Map<String, Object> experiencePrior) {
        this(mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, availableTools, query, experiencePrior,
            new AgentPlanBudgetPolicy.BudgetCaps(null, null, null), null, null, List.of());
    }

    public PlannerValidationContext(List<String> mandatoryTools,
                                    boolean requireToolBeforeFinal,
                                    boolean requireDocumentWebVerification,
                                    String documentSearchTool,
                                    String verificationWebSearchTool,
                                    List<String> availableTools,
                                    String query) {
        this(mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, availableTools, query, Map.of(),
            new AgentPlanBudgetPolicy.BudgetCaps(null, null, null), null, null, List.of());
    }
}
