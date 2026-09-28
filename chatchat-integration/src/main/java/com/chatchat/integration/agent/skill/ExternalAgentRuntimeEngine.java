package com.chatchat.integration.agent.skill;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.runtime.skill.api.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.port.outbound.ExternalAgentEngine;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Governed external Agent adapter for registered A2A or structured HTTP Agent endpoints. */
@Component
public class ExternalAgentRuntimeEngine implements ExternalAgentEngine {
    private static final String ENGINE = "EXTERNAL_AGENT";
    private final RegisteredExternalAgentExecutor executor;

    public ExternalAgentRuntimeEngine(RegisteredExternalAgentExecutor executor) { this.executor = executor; }
    @Override public String engineId() { return ENGINE; }
    @Override public RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request) {
        return executor.execute(request, ENGINE,
            Set.of(AgentDescriptor.Protocol.A2A_HTTP_JSON, AgentDescriptor.Protocol.HTTP_JSON));
    }
    @Override public AgentRuntimeHealthResult health(AgentRuntimeHealthRequest request) {
        return executor.health(request, ENGINE,
            Set.of(AgentDescriptor.Protocol.A2A_HTTP_JSON, AgentDescriptor.Protocol.HTTP_JSON));
    }
}
