package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.AgentOrchestrator;

import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orchestrator-facing port for projecting durable workflow completion state. */
public interface AgentWorkflowStatePort {

    public Map<String, Object> attributesWithCompletedTools(Map<String, Object> runtimeAttributes,
                                                     Set<String> completedTools);

    public Map<String, Object> attributesWithCompletedWorkflowState(Map<String, Object> runtimeAttributes,
                                                             Set<String> completedTools,
                                                             List<InteractionToolTrace> traces);

    public void rememberCompletedWorkflowTool(Set<String> completedTools, AgentOrchestrator.ToolCallExecution execution);

    public Set<String> completedToolsFromTraces(List<InteractionToolTrace> traces);

    public Set<String> completedToolsFromEvents(List<AgentRunEvent> events);

    public boolean isConfirmationRequired(AgentOrchestrator.ToolCallExecution execution);
}
