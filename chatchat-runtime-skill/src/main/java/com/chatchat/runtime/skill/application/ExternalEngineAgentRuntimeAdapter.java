package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.port.outbound.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.port.outbound.ExternalAgentEngine;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Delegates Google ADK or external protocols only to explicitly registered engine drivers. */
public final class ExternalEngineAgentRuntimeAdapter implements AgentRuntimeAdapter {
    private final List<ExternalAgentEngine> engines;

    public ExternalEngineAgentRuntimeAdapter(List<ExternalAgentEngine> engines) {
        this.engines = engines == null ? List.of() : List.copyOf(engines);
    }

    @Override public String adapterId() { return "external-agent-engines"; }
    @Override public boolean supports(String engine) {
        String requested = normalize(engine);
        return engines.stream().anyMatch(item -> normalize(item.engineId()).equals(requested));
    }
    @Override public RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request) {
        return engines.stream().filter(item -> normalize(item.engineId()).equals(normalize(request.engine())))
            .findFirst().map(item -> item.execute(request)).orElseGet(() ->
                new RuntimeAgentExecutionResult("ENGINE_NOT_REGISTERED", "", Map.of("engine", request.engine())));
    }
    @Override public AgentRuntimeHealthResult health(AgentRuntimeHealthRequest request) {
        return engines.stream().filter(item -> normalize(item.engineId()).equals(normalize(request.engine())))
            .findFirst().map(item -> item.health(request)).orElseGet(() ->
                new AgentRuntimeHealthResult("ENGINE_NOT_REGISTERED", Map.of("engine", request.engine())));
    }
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
