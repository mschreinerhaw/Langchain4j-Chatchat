package com.chatchat.agents.runtime.federation;

import com.chatchat.agents.runtime.analysis.workflow.AnalysisOperatorRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.StructuredDataEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class KnowledgeAgentEvidenceSupplementTest {
    @Test void structuredSupplementRequiresProviderGrantAndAuthorizedLocalSkill() {
        AtomicInteger calls = new AtomicInteger();
        AnalysisCapabilityOperator operator = new AnalysisCapabilityOperator() {
            @Override public AnalysisCapability capability() { return AnalysisCapability.STRUCTURED_DATA; }
            @Override public boolean available(AnalysisContext context) {
                return context.attributes().containsKey("runtime.analysis.dataTemplateId");
            }
            @Override public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
                calls.incrementAndGet();
                return new WorkflowExecutionResult(List.of(new StructuredDataEvidence("data-1", "sales",
                    "SALES_TOTAL", 1, "2026-09-25", "{\"rows\":[{\"total\":42}]}",
                    Map.of("tenantId", scope.tenantId(), "remoteProjection", Map.of("rowCount", 1)))),
                    Map.of(), List.of());
            }
        };
        @SuppressWarnings("unchecked") ObjectProvider<AnalysisOperatorRegistry> operators = mock(ObjectProvider.class);
        when(operators.getIfAvailable()).thenReturn(new AnalysisOperatorRegistry(List.of(operator)));
        @SuppressWarnings("unchecked") ObjectProvider<SkillExecutionScopePort> scopes = mock(ObjectProvider.class);
        SkillExecutionScopePort resolver = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), true, true);
        when(scopes.getIfAvailable()).thenReturn(resolver);
        var supplement = new KnowledgeAgentEvidenceSupplement(mock(KnowledgeSkillExecutionUnit.class),
            scopes, operators);
        var request = request();
        var requirement = new EvidenceRequirement("STRUCTURED_DATA", true, 1, "approved template");

        assertThat(new RemoteAgentEvidenceProjector(new ObjectMapper()).project(request).metadata()).isEmpty();

        assertThat(supplement.supplement(agent(false), request, requirement)).isEmpty();
        assertThat(supplement.supplement(agent(true), request, requirement)).singleElement()
            .isInstanceOf(StructuredDataEvidence.class);
        assertThat(calls.get()).isEqualTo(1);
        when(scopes.getIfAvailable()).thenReturn((tenant, user, skill, docs, tags) ->
            SkillExecutionScopePort.EffectiveScope.denied(List.of()));
        assertThat(supplement.supplement(agent(true), request, requirement)).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    private AgentDescriptor agent(boolean allowed) {
        return new AgentDescriptor("group.sales", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://group.example/a2a"),
            Set.of(CapabilityId.parse("finance.sales.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of("sales"),
            Set.of("StructuredDataEvidence"), null, "", 1, true,
            Map.of("supplementCapabilities", allowed ? List.of("STRUCTURED_DATA") : List.of()));
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(null, "exec-1", CapabilityId.parse("finance.sales.v1"),
            new AgentExecutionRequest.TaskContract("analysis", "analyze sales", Map.of()),
            EvidenceBundle.empty(null), Set.of(), new AgentExecutionRequest.Constraints(1000, 2,
                true, true, Set.of("sales")), null,
            new KernelDataScope("tenant", "user", "request", null, "run", null, Map.of()),
            Map.of("localSkillId", "sales-skill", "runtime.analysis.dataTemplateId", "SALES_TOTAL",
                "runtime.analysis.dataAssetName", "sales", "runtime.analysis.dataEnvironment", "PROD",
                "runtime.analysis.dataParameters", Map.of()));
    }
}
