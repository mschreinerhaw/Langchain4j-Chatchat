package com.chatchat.agents.orchestration;

import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.List;
import java.util.Map;

/** Orchestrator-facing decision port; implementations own tool and policy resolution strategy. */
public interface AgentWorkflowDecisionPort {

    WorkflowMandatoryResolution resolveWorkflowMandatoryTools(List<String> tools,
                                                               Map<String, Object> runtimeAttributes,
                                                               String query);

    ToolExecutionDecision resolveToolExecution(String requestedToolName,
                                                boolean required,
                                                String condition,
                                                Map<String, Object> conditionContext,
                                                List<String> availableTools,
                                                List<InteractionToolTrace> traces);

    boolean policyAllowsEarlyFinal(Map<String, Object> runtimeAttributes);

    void recordWorkflowDecision(Map<String, Object> metadata, ToolExecutionDecision decision);

    List<Map<String, Object>> decisionRecords(List<ToolExecutionDecision> decisions);
}
