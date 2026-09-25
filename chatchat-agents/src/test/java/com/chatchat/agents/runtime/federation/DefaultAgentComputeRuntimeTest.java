package com.chatchat.agents.runtime.federation;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentGatewayPort;
import com.chatchat.common.runtime.agent.AgentEvidenceSupplementPort;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.agent.AgentProvider;
import com.chatchat.common.runtime.agent.LocalAgentExecutionPort;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.common.runtime.capability.CapabilityId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAgentComputeRuntimeTest {
    private static final CapabilityId CAPABILITY = CapabilityId.parse("finance.portfolio-analysis.v1");

    @Test void supplementsOnceAndResumesSameRemoteExecution() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(List.of());
        registry.register(remoteDescriptor(Set.of("ToolAnalysisEvidence", "DocumentAnalysisEvidence"), Set.of("portfolio")));
        AtomicInteger resumes = new AtomicInteger();
        AgentGatewayPort gateway = new AgentGatewayPort() {
            @Override public AgentExecutionOutcome invoke(AgentDescriptor agent, AgentExecutionRequest request) {
                return new AgentExecutionOutcome(null, request.executionId(), agent.agentId(),
                    AgentExecutionOutcome.Status.INPUT_REQUIRED, List.of(), List.of(),
                    List.of(new EvidenceRequirement("RULE_LOOKUP", true, 1, "source")), List.of(),
                    "", "", Map.of(), Map.of());
            }
            @Override public AgentExecutionOutcome resume(AgentDescriptor agent, AgentExecutionRequest request) {
                resumes.incrementAndGet();
                assertThat(request.evidence().evidence()).singleElement().satisfies(value ->
                    assertThat(value.evidenceId()).isEqualTo("skill:rule-1"));
                return outcome(request, agent.agentId(), AgentExecutionOutcome.Status.COMPLETED,
                    List.of(new AgentExecutionOutcome.GroundedClaim("C1", "grounded",
                        List.of("skill:rule-1"), .9)), "");
            }
        };
        AgentEvidenceSupplementPort supplement = (agent, request, requirement) -> List.of(
            new DocumentAnalysisEvidence("skill:rule-1", "doc-1", "chunk-1", "Policy", "Rules",
                "doc-1#chunk-1", "private text", .9,
                Map.of("remoteProjection", Map.of("text", "approved summary"))));
        @SuppressWarnings("unchecked") ObjectProvider<AgentGatewayPort> gatewayProvider = mock(ObjectProvider.class);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        @SuppressWarnings("unchecked") ObjectProvider<LocalAgentExecutionPort> adapters = mock(ObjectProvider.class);
        AgentExecutionRequest original = request(Set.of("portfolio"));
        AgentExecutionRequest bounded = new AgentExecutionRequest(null, original.executionId(),
            original.capability(), original.task(), original.evidence(), original.capabilityGrants(),
            new AgentExecutionRequest.Constraints(5000, 2, true, true, Set.of("portfolio")),
            original.outputContract(), original.scope(), Map.of());
        var runtime = new DefaultAgentComputeRuntime(new AgentCapabilityPlanner(registry),
            new AgentOutcomeVerifier(), gatewayProvider, List.of(), adapters,
            new RemoteAgentEvidenceProjector(new ObjectMapper()), supplement, new AgentHealthTracker());

        assertThat(runtime.execute(bounded).status()).isEqualTo(AgentExecutionOutcome.Status.COMPLETED);
        assertThat(resumes.get()).isEqualTo(1);
    }

    @Test void fallsBackFromFailedRemoteAgentToGroundedLocalProvider() {
        AgentProvider local = localProvider();
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(List.of(local));
        registry.register(remoteDescriptor(Set.of("ToolAnalysisEvidence"), Set.of("portfolio")));
        AgentGatewayPort gateway = (agent, request) -> outcome(request, agent.agentId(),
            AgentExecutionOutcome.Status.FAILED, List.of(), "REMOTE_FAILED");
        @SuppressWarnings("unchecked") ObjectProvider<AgentGatewayPort> gatewayProvider = mock(ObjectProvider.class);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        @SuppressWarnings("unchecked") ObjectProvider<LocalAgentExecutionPort> localAdapters = mock(ObjectProvider.class);
        when(localAdapters.orderedStream()).thenReturn(java.util.stream.Stream.empty());
        DefaultAgentComputeRuntime runtime = new DefaultAgentComputeRuntime(
            new AgentCapabilityPlanner(registry), new AgentOutcomeVerifier(), gatewayProvider, List.of(local),
            localAdapters, new RemoteAgentEvidenceProjector(new ObjectMapper()));

        AgentExecutionOutcome result = runtime.execute(request(Set.of("portfolio")));

        assertThat(result.status()).isEqualTo(AgentExecutionOutcome.Status.COMPLETED);
        assertThat(result.providerAgentId()).isEqualTo("local.investment");
        assertThat(result.claims()).singleElement().satisfies(claim ->
            assertThat(claim.evidenceIds()).containsExactly("E1"));
    }

    @Test void replansToNextPolicyAdmittedProvider() {
        AgentProvider local = localProvider();
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(List.of(local));
        registry.register(remoteDescriptor(Set.of("ToolAnalysisEvidence"), Set.of("portfolio")));
        AgentGatewayPort gateway = (agent, request) -> outcome(request, agent.agentId(),
            AgentExecutionOutcome.Status.REPLAN_REQUIRED, List.of(), "REPLAN");
        @SuppressWarnings("unchecked") ObjectProvider<AgentGatewayPort> gatewayProvider = mock(ObjectProvider.class);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        @SuppressWarnings("unchecked") ObjectProvider<LocalAgentExecutionPort> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream()).thenReturn(java.util.stream.Stream.empty());
        var runtime = new DefaultAgentComputeRuntime(new AgentCapabilityPlanner(registry),
            new AgentOutcomeVerifier(), gatewayProvider, List.of(local), adapters,
            new RemoteAgentEvidenceProjector(new ObjectMapper()));

        AgentExecutionOutcome result = runtime.execute(request(Set.of("portfolio")));

        assertThat(result.status()).isEqualTo(AgentExecutionOutcome.Status.COMPLETED);
        assertThat(result.providerAgentId()).isEqualTo("local.investment");
        assertThat(result.metadata()).containsKey("runtimeEvidenceBundle");
    }

    @Test void fallsBackWhenRemoteReturnsOnlyUnstructuredPartialText() {
        AgentProvider local = localProvider();
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(List.of(local));
        registry.register(remoteDescriptor(Set.of("ToolAnalysisEvidence"), Set.of("portfolio")));
        AgentGatewayPort gateway = (agent, request) -> new AgentExecutionOutcome(null,
            request.executionId(), agent.agentId(), AgentExecutionOutcome.Status.PARTIAL,
            List.of(), List.of(new AgentExecutionOutcome.Artifact("A1", "text/plain", "unverified", Map.of())),
            List.of(), List.of(), "", "", Map.of(), Map.of());
        @SuppressWarnings("unchecked") ObjectProvider<AgentGatewayPort> gatewayProvider = mock(ObjectProvider.class);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        @SuppressWarnings("unchecked") ObjectProvider<LocalAgentExecutionPort> localAdapters = mock(ObjectProvider.class);
        DefaultAgentComputeRuntime runtime = new DefaultAgentComputeRuntime(
            new AgentCapabilityPlanner(registry), new AgentOutcomeVerifier(), gatewayProvider, List.of(local),
            localAdapters, new RemoteAgentEvidenceProjector(new ObjectMapper()));

        assertThat(runtime.execute(request(Set.of("portfolio"))).providerAgentId()).isEqualTo("local.investment");
    }

    @Test void blocksRemoteAgentWhenEvidenceTypeWasNotGranted() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(List.of());
        registry.register(remoteDescriptor(Set.of("DocumentAnalysisEvidence"), Set.of("portfolio")));
        AgentCapabilityPlanner planner = new AgentCapabilityPlanner(registry);

        assertThat(planner.candidates(request(Set.of("portfolio")))).isEmpty();
    }

    @Test void blocksRemoteAgentWithoutExplicitTenantGrant() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(List.of());
        registry.register(remoteDescriptor(Set.of("ToolAnalysisEvidence"), Set.of("portfolio"), false));
        assertThat(new AgentCapabilityPlanner(registry).candidates(request(Set.of("portfolio")))).isEmpty();
    }

    @Test void remoteEvidenceRequiresExplicitFieldProjection() {
        AgentExecutionRequest original = request(Set.of("portfolio"));
        RemoteAgentEvidenceProjector projector = new RemoteAgentEvidenceProjector(new ObjectMapper());
        AgentExecutionRequest projected = projector.project(original);
        assertThat(projected.evidence().evidence()).isEmpty();

        EvidenceBundle approved = new EvidenceBundle(EvidenceBundle.SCHEMA_VERSION,
            List.of(new ToolAnalysisEvidence("E1", "position-summary", "call-1", "RAW-PRIVATE",
                Map.of("remoteProjection", Map.of("positionCount", 3)))), List.of(), Map.of());
        AgentExecutionRequest withProjection = new AgentExecutionRequest(null, original.executionId(),
            original.capability(), original.task(), approved, Set.of(), original.constraints(),
            original.outputContract(), original.scope(), Map.of());
        AgentExecutionRequest safe = projector.project(withProjection);
        assertThat(safe.evidence().evidence()).singleElement().satisfies(value -> {
            assertThat(value.content()).contains("positionCount").doesNotContain("RAW-PRIVATE");
            assertThat(value.evidenceId()).isEqualTo("E1");
        });
        AgentExecutionOutcome guessedClaim = outcome(original, "group.investment",
            AgentExecutionOutcome.Status.COMPLETED,
            List.of(new AgentExecutionOutcome.GroundedClaim("C1", "guess", List.of("E1"), 0.9)), "");
        assertThat(new AgentOutcomeVerifier().verify(remoteDescriptor(Set.of("ToolAnalysisEvidence"),
            Set.of("portfolio")), projected, guessedClaim).accepted()).isFalse();
    }

    @Test void rejectsProviderClaimsThatReferenceUnknownEvidence() {
        AgentOutcomeVerifier verifier = new AgentOutcomeVerifier();
        AgentExecutionRequest request = request(Set.of("portfolio"));
        AgentExecutionOutcome invalid = outcome(request, "group.investment",
            AgentExecutionOutcome.Status.COMPLETED,
            List.of(new AgentExecutionOutcome.GroundedClaim("C1", "unsupported", List.of("E999"), 0.9)), "");

        assertThat(verifier.verify(remoteDescriptor(Set.of("ToolAnalysisEvidence"), Set.of("portfolio")),
            request, invalid).accepted()).isFalse();
    }

    private AgentProvider localProvider() {
        AgentDescriptor descriptor = new AgentDescriptor("local.investment", "v1", AgentDescriptor.Origin.LOCAL,
            AgentDescriptor.Protocol.LOCAL, null, Set.of(CAPABILITY), AgentDescriptor.TrustLevel.INTERNAL,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 10, true, Map.of());
        return new AgentProvider() {
            @Override public AgentDescriptor descriptor() { return descriptor; }
            @Override public AgentExecutionOutcome execute(AgentExecutionRequest request) {
                return outcome(request, descriptor.agentId(), AgentExecutionOutcome.Status.COMPLETED,
                    List.of(new AgentExecutionOutcome.GroundedClaim("C1", "grounded", List.of("E1"), 0.9)), "");
            }
        };
    }

    private AgentDescriptor remoteDescriptor(Set<String> evidenceTypes, Set<String> domains) {
        return remoteDescriptor(evidenceTypes, domains, true);
    }

    private AgentDescriptor remoteDescriptor(Set<String> evidenceTypes, Set<String> domains, boolean grantTenant) {
        return new AgentDescriptor("group.investment", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://agents.example/a2a"), Set.of(CAPABILITY),
            AgentDescriptor.TrustLevel.GROUP_TRUSTED, AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, domains,
            evidenceTypes, null, "env:GROUP_AGENT_TOKEN", 100, true,
            grantTenant ? Map.of("allowedTenantIds", List.of("tenant-1")) : Map.of());
    }

    private AgentExecutionRequest request(Set<String> domains) {
        EvidenceBundle evidence = new EvidenceBundle(EvidenceBundle.SCHEMA_VERSION,
            List.of(new ToolAnalysisEvidence("E1", "position-summary", "call-1", "{}", Map.of())),
            List.of(), Map.of());
        return new AgentExecutionRequest(AgentExecutionRequest.SCHEMA_VERSION, "exec-1", CAPABILITY,
            new AgentExecutionRequest.TaskContract("portfolio-analysis", "analyze", Map.of()), evidence, Set.of(),
            new AgentExecutionRequest.Constraints(1_000, 1, true, true, domains),
            AgentExecutionRequest.OutputContract.defaults(),
            new KernelDataScope("tenant-1", "user-1", "request-1", "conversation-1", "run-1", "test", Map.of()),
            Map.of());
    }

    private AgentExecutionOutcome outcome(AgentExecutionRequest request, String provider,
                                          AgentExecutionOutcome.Status status,
                                          List<AgentExecutionOutcome.GroundedClaim> claims, String error) {
        return new AgentExecutionOutcome(AgentExecutionOutcome.SCHEMA_VERSION, request.executionId(), provider,
            status, claims, List.of(), List.of(), List.of(), error, error, Map.of(), Map.of());
    }
}
