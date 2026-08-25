package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orchestrator-facing port for projecting durable workflow completion state. */
interface AgentWorkflowStatePort {

    Map<String, Object> attributesWithCompletedTools(Map<String, Object> runtimeAttributes,
                                                     Set<String> completedTools);

    Map<String, Object> attributesWithCompletedWorkflowState(Map<String, Object> runtimeAttributes,
                                                             Set<String> completedTools,
                                                             List<InteractionToolTrace> traces);

    void rememberCompletedWorkflowTool(Set<String> completedTools, AgentOrchestrator.ToolCallExecution execution);

    Set<String> completedToolsFromTraces(List<InteractionToolTrace> traces);

    Set<String> completedToolsFromEvents(List<AgentRunEvent> events);

    boolean isConfirmationRequired(AgentOrchestrator.ToolCallExecution execution);
}
