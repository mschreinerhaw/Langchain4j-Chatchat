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
    Object authoritativeWorkflowDag
) {
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
            budgetCaps, null);
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
            new AgentPlanBudgetPolicy.BudgetCaps(null, null, null), null);
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
            new AgentPlanBudgetPolicy.BudgetCaps(null, null, null), null);
    }
}
