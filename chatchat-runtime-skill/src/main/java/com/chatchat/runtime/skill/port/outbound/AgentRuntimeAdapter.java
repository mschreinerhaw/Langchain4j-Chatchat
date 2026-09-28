package com.chatchat.runtime.skill.port.outbound;

import com.chatchat.runtime.skill.api.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionResult;

import java.util.Map;

/** Stable boundary for LangChain4j, Google ADK, OpenAI-compatible and external agent engines. */
public interface AgentRuntimeAdapter {
    String adapterId();
    default int priority() { return 0; }
    boolean supports(String engine);
    RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request);
    default AgentRuntimeHealthResult health(AgentRuntimeHealthRequest request) {
        return new AgentRuntimeHealthResult("UNKNOWN", Map.of("adapterId", adapterId()));
    }
}
