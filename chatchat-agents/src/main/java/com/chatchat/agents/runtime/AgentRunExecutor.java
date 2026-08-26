package com.chatchat.agents.runtime;

import com.chatchat.common.runtime.workflow.RuntimeWorkflow;

/**
 * Runtime-facing execution port implemented by the upper orchestration layer.
 *
 * <p>This interface keeps Runtime independent from the concrete orchestrator while allowing the
 * application composition layer to inject its implementation.</p>
 */
@FunctionalInterface
public interface AgentRunExecutor extends RuntimeWorkflow<AgentRunRequest, AgentRunResult> {
    @Override
    default String workflowId() {
        return "agent-run-executor";
    }
}
