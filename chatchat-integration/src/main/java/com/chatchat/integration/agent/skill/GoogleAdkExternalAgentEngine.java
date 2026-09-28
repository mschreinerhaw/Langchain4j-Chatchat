package com.chatchat.integration.agent.skill;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.runtime.skill.api.agent.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.agent.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.port.outbound.ExternalAgentEngine;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Google ADK adapter using the registered ADK Agent's official A2A endpoint. */
@Component
public class GoogleAdkExternalAgentEngine implements ExternalAgentEngine {
    private static final String ENGINE = "GOOGLE_ADK";
    private final RegisteredExternalAgentExecutor executor;

    public GoogleAdkExternalAgentEngine(RegisteredExternalAgentExecutor executor) { this.executor = executor; }
    @Override public String engineId() { return ENGINE; }
    @Override public RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request) {
        return executor.execute(request, ENGINE, Set.of(AgentDescriptor.Protocol.A2A_HTTP_JSON));
    }
    @Override public AgentRuntimeHealthResult health(AgentRuntimeHealthRequest request) {
        return executor.health(request, ENGINE, Set.of(AgentDescriptor.Protocol.A2A_HTTP_JSON));
    }
}
