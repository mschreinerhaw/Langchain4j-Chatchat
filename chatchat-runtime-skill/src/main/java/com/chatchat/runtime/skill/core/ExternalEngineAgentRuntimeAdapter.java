package com.chatchat.runtime.skill.core;

import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.spi.ExternalAgentEngine;

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
    @Override public ExecutionResult execute(ExecutionRequest request) {
        return engines.stream().filter(item -> normalize(item.engineId()).equals(normalize(request.engine())))
            .findFirst().map(item -> item.execute(request)).orElseGet(() ->
                new ExecutionResult("ENGINE_NOT_REGISTERED", "", Map.of("engine", request.engine())));
    }
    @Override public HealthResult health(HealthRequest request) {
        return engines.stream().filter(item -> normalize(item.engineId()).equals(normalize(request.engine())))
            .findFirst().map(item -> item.health(request)).orElseGet(() ->
                new HealthResult("ENGINE_NOT_REGISTERED", Map.of("engine", request.engine())));
    }
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
