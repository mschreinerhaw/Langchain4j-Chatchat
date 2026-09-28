package com.chatchat.runtime.skill.spi;

public interface AgentRuntimeDispatcher {
    AgentRuntimeAdapter.ExecutionResult execute(AgentRuntimeAdapter.ExecutionRequest request);
}
