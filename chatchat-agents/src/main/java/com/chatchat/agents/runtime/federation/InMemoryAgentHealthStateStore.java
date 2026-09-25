package com.chatchat.agents.runtime.federation;

import java.util.concurrent.ConcurrentHashMap;

/** Test fallback preserving the same atomic state transition as the durable store. */
final class InMemoryAgentHealthStateStore implements AgentHealthStateStore {
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    @Override public State read(String agentId) { return states.getOrDefault(agentId, State.empty()); }
    @Override public State record(String agentId, boolean failed, long latencyMs, long nowEpochMs) {
        return states.compute(agentId, (ignored, current) ->
            (current == null ? State.empty() : current).next(failed, latencyMs, nowEpochMs));
    }
}
