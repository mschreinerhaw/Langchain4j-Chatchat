package com.chatchat.integration.agent;

import com.chatchat.agents.runtime.federation.AgentHealthStateStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_provider_health")
public class AgentHealthEntity {
    @Id @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;
    @Column(name = "samples", nullable = false)
    private int samples;
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;
    @Column(name = "ewma_latency_ms", nullable = false)
    private long ewmaLatencyMs;
    @Column(name = "open_until_epoch_ms", nullable = false)
    private long openUntilEpochMs;

    protected AgentHealthEntity() { }

    AgentHealthEntity(String agentId) { this.agentId = agentId; }

    AgentHealthStateStore.State state() {
        return new AgentHealthStateStore.State(samples, consecutiveFailures, ewmaLatencyMs, openUntilEpochMs);
    }

    void apply(AgentHealthStateStore.State state) {
        samples = state.samples();
        consecutiveFailures = state.consecutiveFailures();
        ewmaLatencyMs = state.ewmaLatencyMs();
        openUntilEpochMs = state.openUntilEpochMs();
    }
}
