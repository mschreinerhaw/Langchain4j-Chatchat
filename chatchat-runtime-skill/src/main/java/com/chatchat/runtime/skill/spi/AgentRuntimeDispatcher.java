package com.chatchat.runtime.skill.spi;

public interface AgentRuntimeDispatcher {
    AgentRuntimeAdapter.ExecutionResult execute(AgentRuntimeAdapter.ExecutionRequest request);
    default AgentRuntimeAdapter.HealthResult health(String engine, java.util.Map<String, Object> attributes) {
        return new AgentRuntimeAdapter.HealthResult("UNKNOWN", java.util.Map.of("engine", engine == null ? "" : engine));
    }
}
