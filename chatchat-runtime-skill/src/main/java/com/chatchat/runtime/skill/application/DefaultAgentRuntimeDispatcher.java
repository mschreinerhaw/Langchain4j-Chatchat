package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.agent.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.agent.AgentRuntimeHealthResult;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.port.outbound.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.port.inbound.AgentRuntimeDispatcher;

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
    public boolean supportsAcquiredData(String engine) {
        return adapters.stream().filter(adapter -> adapter.supports(engine)).findFirst()
            .map(AgentRuntimeAdapter::supportsAcquiredData).orElse(false);
    }

    @Override
    public RuntimeAgentExecutionResult execute(RuntimeAgentExecutionRequest request) {
        if (request == null || request.engine() == null || request.engine().isBlank())
            return new RuntimeAgentExecutionResult("ENGINE_REQUIRED", "", Map.of());
        List<AgentRuntimeAdapter> supported = adapters.stream()
            .filter(adapter -> adapter.supports(request.engine())).toList();
        if (supported.isEmpty()) return new RuntimeAgentExecutionResult(
            "ENGINE_NOT_REGISTERED", "", Map.of("engine", request.engine()));
        AgentRuntimeAdapter selected = supported.get(0);
        return selected.execute(request);
    }

    @Override
    public AgentRuntimeHealthResult health(String engine, Map<String, Object> attributes) {
        if (engine == null || engine.isBlank())
            return new AgentRuntimeHealthResult("ENGINE_REQUIRED", Map.of());
        return adapters.stream().filter(adapter -> adapter.supports(engine)).findFirst()
            .map(adapter -> adapter.health(new AgentRuntimeHealthRequest(engine, attributes)))
            .orElseGet(() -> new AgentRuntimeHealthResult(
                "ENGINE_NOT_REGISTERED", Map.of("engine", engine)));
    }
}
