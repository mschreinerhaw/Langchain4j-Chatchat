package com.chatchat.runtime.skill.core;

import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.spi.AgentRuntimeDispatcher;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Exact engine dispatch with no implicit provider fallback. */
public final class DefaultAgentRuntimeDispatcher implements AgentRuntimeDispatcher {
    private final List<AgentRuntimeAdapter> adapters;

    public DefaultAgentRuntimeDispatcher(List<AgentRuntimeAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : adapters.stream()
            .sorted(Comparator.comparingInt(AgentRuntimeAdapter::priority).reversed()
                .thenComparing(AgentRuntimeAdapter::adapterId)).toList();
    }

    @Override
    public AgentRuntimeAdapter.ExecutionResult execute(AgentRuntimeAdapter.ExecutionRequest request) {
        if (request == null || request.engine() == null || request.engine().isBlank())
            return new AgentRuntimeAdapter.ExecutionResult("ENGINE_REQUIRED", "", Map.of());
        List<AgentRuntimeAdapter> supported = adapters.stream()
            .filter(adapter -> adapter.supports(request.engine())).toList();
        if (supported.isEmpty()) return new AgentRuntimeAdapter.ExecutionResult(
            "ENGINE_NOT_REGISTERED", "", Map.of("engine", request.engine()));
        AgentRuntimeAdapter selected = supported.get(0);
        return selected.execute(request);
    }

    @Override
    public AgentRuntimeAdapter.HealthResult health(String engine, Map<String, Object> attributes) {
        if (engine == null || engine.isBlank())
            return new AgentRuntimeAdapter.HealthResult("ENGINE_REQUIRED", Map.of());
        return adapters.stream().filter(adapter -> adapter.supports(engine)).findFirst()
            .map(adapter -> adapter.health(new AgentRuntimeAdapter.HealthRequest(engine, attributes)))
            .orElseGet(() -> new AgentRuntimeAdapter.HealthResult(
                "ENGINE_NOT_REGISTERED", Map.of("engine", engine)));
    }
}
