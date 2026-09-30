package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.*;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LocalAgentAdmissionTest {
    @Test void authorizesTargetIndependentlyFromEvidenceSourceAndFailsClosedOnRevocation() {
        var registry = mock(AgentRegistryPort.class);
        var capability = CapabilityId.parse("local.review.v1");
        var agent = new AgentDescriptor("local.skill.review", "v1", AgentDescriptor.Origin.LOCAL,
            AgentDescriptor.Protocol.LOCAL, null, Set.of(capability), AgentDescriptor.TrustLevel.INTERNAL,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 50, true,
            Map.of("skillId", "review", "marketStatus", "published"));
        when(registry.findByCapability(capability)).thenReturn(List.of(agent));
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) -> {
            assertThat(skill).isEqualTo("review");
            assertThat(tenant).isEqualTo("tenant");
            assertThat(user).isEqualTo("user");
            return new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), true, allowed.get());
        };
        var request = new AgentExecutionRequest(null, "execution", capability,
            new AgentExecutionRequest.TaskContract("review", "Review", Map.of()), EvidenceBundle.empty("none"),
            Set.of(), new AgentExecutionRequest.Constraints(1000, 1, true, true, Set.of()),
            AgentExecutionRequest.OutputContract.defaults(),
            new KernelDataScope("tenant", "user", "request", null, "run", null, Map.of()),
            Map.of("localSkillId", "different-evidence-source", AgentExecutionRequest.TARGET_AGENT_METADATA_KEY, agent.agentId()));
        var planner = new AgentCapabilityPlanner(registry, new AgentHealthTracker(), scopes);
        assertThat(planner.candidates(request)).containsExactly(agent);
        allowed.set(false);
        assertThat(planner.candidates(request)).isEmpty();
    }
}
