package com.chatchat.integration.agent.skill;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.spi.ExternalAgentEngine;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Google ADK adapter using the registered ADK Agent's official A2A endpoint. */
@Component
public class GoogleAdkExternalAgentEngine implements ExternalAgentEngine {
    private static final String ENGINE = "GOOGLE_ADK";
    private final RegisteredExternalAgentExecutor executor;

    public GoogleAdkExternalAgentEngine(RegisteredExternalAgentExecutor executor) { this.executor = executor; }
    @Override public String engineId() { return ENGINE; }
    @Override public AgentRuntimeAdapter.ExecutionResult execute(AgentRuntimeAdapter.ExecutionRequest request) {
        return executor.execute(request, ENGINE, Set.of(AgentDescriptor.Protocol.A2A_HTTP_JSON));
    }
    @Override public AgentRuntimeAdapter.HealthResult health(AgentRuntimeAdapter.HealthRequest request) {
        return executor.health(request, ENGINE, Set.of(AgentDescriptor.Protocol.A2A_HTTP_JSON));
    }
}
