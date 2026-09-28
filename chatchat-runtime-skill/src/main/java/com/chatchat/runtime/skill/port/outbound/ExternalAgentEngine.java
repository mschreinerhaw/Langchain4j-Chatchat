package com.chatchat.runtime.skill.port.outbound;

import com.chatchat.runtime.skill.api.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionResult;

/** Extension point implemented by Google ADK and remote/external Agent protocol drivers. */
public interface ExternalAgentEngine {
    String engineId();
    RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request);
    default AgentRuntimeHealthResult health(AgentRuntimeHealthRequest request) {
        return new AgentRuntimeHealthResult("UNKNOWN", java.util.Map.of("engine", engineId()));
    }
}
