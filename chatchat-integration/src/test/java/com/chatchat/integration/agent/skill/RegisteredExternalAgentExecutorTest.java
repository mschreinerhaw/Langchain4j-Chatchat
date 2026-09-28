package com.chatchat.integration.agent.skill;

import com.chatchat.common.runtime.agent.AgentCardDiscoveryPort;
import com.chatchat.common.runtime.agent.AgentCredentialResolver;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentGatewayPort;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.chatchat.runtime.skill.api.resolution.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.agent.AgentRuntimeHealthRequest;
import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import com.chatchat.runtime.skill.api.workflow.ResolvedWorkflow;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.workflow.WorkflowType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisteredExternalAgentExecutorTest {
    @Test
    void googleAdkUsesOnlyTheAuthorizedDatabaseRegisteredA2aAgent() {
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        AgentGatewayPort gateway = mock(AgentGatewayPort.class);
        AgentCardDiscoveryPort cards = mock(AgentCardDiscoveryPort.class);
        AgentDescriptor descriptor = descriptor("google-agent", "GOOGLE_ADK");
        when(registry.find("google-agent")).thenReturn(Optional.of(descriptor));
        when(gateway.invoke(org.mockito.ArgumentMatchers.eq(descriptor), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new AgentExecutionOutcome(null, "run-1", "google-agent",
                AgentExecutionOutcome.Status.COMPLETED,
                List.of(new AgentExecutionOutcome.GroundedClaim("c1", "verified", List.of(), 0.9)),
                List.of(), List.of(), List.of(), "", "", Map.of(), Map.of()));
        GoogleAdkExternalAgentEngine engine = new GoogleAdkExternalAgentEngine(
            executor(registry, gateway, cards));

        var result = engine.execute(request("GOOGLE_ADK", "google-agent"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.output()).isEqualTo("verified");
        ArgumentCaptor<com.chatchat.common.runtime.agent.AgentExecutionRequest> sent =
            ArgumentCaptor.forClass(com.chatchat.common.runtime.agent.AgentExecutionRequest.class);
        verify(gateway).invoke(org.mockito.ArgumentMatchers.eq(descriptor), sent.capture());
        assertThat(sent.getValue().scope().tenantId()).isEqualTo("tenant");
        assertThat(sent.getValue().task().instruction()).isEqualTo("analyze");
    }

    @Test
    void refusesAnAgentOutsideTheDatabaseAuthorizedSkillScope() {
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        AgentGatewayPort gateway = mock(AgentGatewayPort.class);
        GoogleAdkExternalAgentEngine engine = new GoogleAdkExternalAgentEngine(
            executor(registry, gateway, mock(AgentCardDiscoveryPort.class)));
        RuntimeAgentExecutionRequest request = request("GOOGLE_ADK", "google-agent");
        AuthorizedSkillScope deniedTarget = new AuthorizedSkillScope(true, List.of(), List.of(), List.of(),
            List.of("different-agent"), List.of("external-workflow"), List.of());
        request = new RuntimeAgentExecutionRequest(request.engine(), request.query(), request.roleContext(),
            request.skill(), deniedTarget, request.workflow(), request.attributes());

        var result = engine.execute(request);

        assertThat(result.status()).isEqualTo("AGENT_NOT_AUTHORIZED");
        verify(gateway, never()).invoke(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void healthCheckDiscoversAndVerifiesTheRegisteredAgentCard() {
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        AgentGatewayPort gateway = mock(AgentGatewayPort.class);
        AgentCardDiscoveryPort cards = mock(AgentCardDiscoveryPort.class);
        AgentDescriptor descriptor = descriptor("google-agent", "GOOGLE_ADK");
        when(registry.find("google-agent")).thenReturn(Optional.of(descriptor));
        when(cards.discoverSummary(descriptor, null)).thenReturn(new AgentCardDiscoveryPort.CardSummary(
            "Google Agent", "v1", List.of("analyze"), true, descriptor.endpoint().toString()));
        GoogleAdkExternalAgentEngine engine = new GoogleAdkExternalAgentEngine(
            executor(registry, gateway, cards));

        var health = engine.health(new AgentRuntimeHealthRequest(
            "GOOGLE_ADK", Map.of("agentId", "google-agent")));

        assertThat(health.status()).isEqualTo("READY");
        assertThat(health.details()).containsEntry("signatureVerified", true);
    }

    @Test
    void externalEngineUsesOnlyAnExplicitlyDeclaredDatabaseAgent() {
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        AgentGatewayPort gateway = mock(AgentGatewayPort.class);
        AgentDescriptor descriptor = new AgentDescriptor("external-agent", "v1", AgentDescriptor.Origin.EXTERNAL,
            AgentDescriptor.Protocol.HTTP_JSON, URI.create("https://agents.example/invoke"),
            Set.of(CapabilityId.parse("finance.analysis.v1")), AgentDescriptor.TrustLevel.PARTNER,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 1, true,
            Map.of("runtimeEngine", "EXTERNAL_AGENT"));
        when(registry.find("external-agent")).thenReturn(Optional.of(descriptor));
        when(gateway.invoke(org.mockito.ArgumentMatchers.eq(descriptor), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new AgentExecutionOutcome(null, "run-1", "external-agent",
                AgentExecutionOutcome.Status.COMPLETED, List.of(),
                List.of(new AgentExecutionOutcome.Artifact("result", "text/plain", "external result", Map.of())),
                List.of(), List.of(), "", "", Map.of(), Map.of()));
        ExternalAgentRuntimeEngine engine = new ExternalAgentRuntimeEngine(
            executor(registry, gateway, mock(AgentCardDiscoveryPort.class)));

        var result = engine.execute(request("EXTERNAL_AGENT", "external-agent"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.output()).isEqualTo("external result");
        assertThat(result.metadata()).containsEntry("agentProtocol", "HTTP_JSON");
    }

    @Test
    void invalidRuntimeConstraintsFailClosedInsteadOfUsingDefaults() {
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        AgentGatewayPort gateway = mock(AgentGatewayPort.class);
        AgentDescriptor descriptor = descriptor("google-agent", "GOOGLE_ADK");
        when(registry.find("google-agent")).thenReturn(Optional.of(descriptor));
        GoogleAdkExternalAgentEngine engine = new GoogleAdkExternalAgentEngine(
            executor(registry, gateway, mock(AgentCardDiscoveryPort.class)));
        RuntimeAgentExecutionRequest base = request("GOOGLE_ADK", "google-agent");
        RuntimeAgentExecutionRequest invalid = new RuntimeAgentExecutionRequest(
            base.engine(), base.query(), base.roleContext(), base.skill(), base.scope(), base.workflow(),
            Map.of("agentId", "google-agent", "capabilityId", "finance.analysis.v1", "timeoutMs", "invalid"));

        var result = engine.execute(invalid);

        assertThat(result.status()).isEqualTo("INVALID_RUNTIME_CONSTRAINT");
        verify(gateway, never()).invoke(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private RegisteredExternalAgentExecutor executor(AgentRegistryPort registry, AgentGatewayPort gateway,
                                                       AgentCardDiscoveryPort cards) {
        @SuppressWarnings("unchecked") ObjectProvider<AgentCredentialResolver> credentials = mock(ObjectProvider.class);
        return new RegisteredExternalAgentExecutor(registry, gateway, cards, credentials);
    }

    private RuntimeAgentExecutionRequest request(String engine, String agentId) {
        SkillDescriptor descriptor = new SkillDescriptor("skill", "1", "Skill", "", "", "DATABASE",
            "source", "", "", 1D, Map.of());
        ResolvedSkill skill = new ResolvedSkill(descriptor, "instructions", List.of(), null, Map.of());
        AuthorizedSkillScope scope = new AuthorizedSkillScope(true, List.of(), List.of(), List.of(),
            List.of(agentId), List.of("external-workflow"), List.of());
        ResolvedWorkflow workflow = new ResolvedWorkflow(
            "external-workflow", WorkflowType.EXTERNAL_AGENT, List.of(), Map.of());
        SkillRoleContext role = new SkillRoleContext("tenant", "user", List.of("role"), List.of(), Map.of());
        return new RuntimeAgentExecutionRequest(engine, "analyze", role, skill, scope, workflow,
            Map.of("agentId", agentId, "runId", "run-1", "capabilityId", "finance.analysis.v1"));
    }

    private AgentDescriptor descriptor(String agentId, String runtimeEngine) {
        return new AgentDescriptor(agentId, "v1", AgentDescriptor.Origin.EXTERNAL,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://agents.example/a2a"),
            Set.of(CapabilityId.parse("finance.analysis.v1")), AgentDescriptor.TrustLevel.PARTNER,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 1, true,
            Map.of("runtimeEngine", runtimeEngine));
    }
}
