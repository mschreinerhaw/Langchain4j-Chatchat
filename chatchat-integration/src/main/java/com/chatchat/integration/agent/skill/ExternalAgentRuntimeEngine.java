package com.chatchat.integration.agent.skill;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.spi.ExternalAgentEngine;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Governed external Agent adapter for registered A2A or structured HTTP Agent endpoints. */
@Component
public class ExternalAgentRuntimeEngine implements ExternalAgentEngine {
    private static final String ENGINE = "EXTERNAL_AGENT";
    private final RegisteredExternalAgentExecutor executor;

    public ExternalAgentRuntimeEngine(RegisteredExternalAgentExecutor executor) { this.executor = executor; }
    @Override public String engineId() { return ENGINE; }
    @Override public AgentRuntimeAdapter.ExecutionResult execute(AgentRuntimeAdapter.ExecutionRequest request) {
        return executor.execute(request, ENGINE,
            Set.of(AgentDescriptor.Protocol.A2A_HTTP_JSON, AgentDescriptor.Protocol.HTTP_JSON));
    }
    @Override public AgentRuntimeAdapter.HealthResult health(AgentRuntimeAdapter.HealthRequest request) {
        return executor.health(request, ENGINE,
            Set.of(AgentDescriptor.Protocol.A2A_HTTP_JSON, AgentDescriptor.Protocol.HTTP_JSON));
    }
}
