package com.chatchat.runtime.skill.port.inbound;

import com.chatchat.runtime.skill.api.agent.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionResult;

public interface AgentRuntimeDispatcher {
    RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request);
    default boolean supportsAcquiredData(String engine) { return false; }
    default AgentRuntimeHealthResult health(String engine, java.util.Map<String, Object> attributes) {
        return new AgentRuntimeHealthResult("UNKNOWN", java.util.Map.of("engine", engine == null ? "" : engine));
    }
}
