package com.chatchat.runtime.skill.spi;

/** Extension point implemented by Google ADK and remote/external Agent protocol drivers. */
public interface ExternalAgentEngine {
    String engineId();
    AgentRuntimeAdapter.ExecutionResult execute(AgentRuntimeAdapter.ExecutionRequest request);
    default AgentRuntimeAdapter.HealthResult health(AgentRuntimeAdapter.HealthRequest request) {
        return new AgentRuntimeAdapter.HealthResult("UNKNOWN", java.util.Map.of("engine", engineId()));
    }
}
