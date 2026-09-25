package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.capability.CapabilityId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentHealthTrackerTest {
    @Test void opensAfterThreeFailuresAndPenalizesSlaMiss() {
        var agent = new AgentDescriptor("remote", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://agent.example/a2a"),
            Set.of(CapabilityId.parse("finance.test.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 50, true,
            Map.of("slaLatencyMs", 100));
        var health = new AgentHealthTracker();
        var failed = new AgentExecutionOutcome(null, "execution", agent.agentId(),
            AgentExecutionOutcome.Status.FAILED, List.of(), List.of(), List.of(), List.of(),
            "REMOTE", "failed", Map.of(), Map.of());
        assertThat(health.available(agent)).isTrue();
        health.record(agent, failed, 250);
        assertThat(health.latencyPenalty(agent)).isPositive();
        health.record(agent, failed, 250);
        health.record(agent, failed, 250);
        assertThat(health.available(agent)).isFalse();
        assertThat(health.snapshot(agent.agentId())).containsEntry("state", "OPEN");
    }
}
