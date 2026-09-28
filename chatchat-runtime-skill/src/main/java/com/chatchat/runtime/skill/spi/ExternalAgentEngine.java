package com.chatchat.runtime.skill.spi;

/** Extension point implemented by Google ADK and remote/external Agent protocol drivers. */
public interface ExternalAgentEngine {
    String engineId();
    AgentRuntimeAdapter.ExecutionResult execute(AgentRuntimeAdapter.ExecutionRequest request);
}
