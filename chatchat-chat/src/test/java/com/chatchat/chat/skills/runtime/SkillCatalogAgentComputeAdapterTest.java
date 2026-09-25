package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.catalog.SkillCatalogChange;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionMode;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.ProjectedAnalysisEvidence;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillCatalogAgentComputeAdapterTest {
    @Test void localAgentProposesEvidenceThenReturnsGroundedClaimWithoutCallingBoundTools() {
        SkillCatalogService skills = mock(SkillCatalogService.class);
        SkillDefinition skill = mock(SkillDefinition.class);
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        when(skill.id()).thenReturn("risk");
        when(skill.boundMcpToolNames()).thenReturn(List.of("sql_query_execute"));
        when(skill.workflowConfig()).thenReturn(Map.of());
        when(skills.list()).thenReturn(List.of(skill));
        when(skills.resolve("risk")).thenReturn(skill);
        var adapter = new SkillCatalogAgentComputeAdapter(skills, runtime, registry, new ObjectMapper());
        adapter.afterPropertiesSet();
        ArgumentCaptor<AgentDescriptor> published = ArgumentCaptor.forClass(AgentDescriptor.class);
        verify(registry).register(published.capture());
        AgentDescriptor descriptor = published.getValue();
        assertThat(descriptor.supportsExecutionMode(AgentExecutionMode.AGENTIC_EXECUTION)).isTrue();
        AtomicInteger turn = new AtomicInteger();
        when(runtime.run(any())).thenAnswer(invocation -> AgentRunResult.builder()
            .status(AgentRunStatus.COMPLETED)
            .answer(turn.getAndIncrement() == 0
                ? "{\"status\":\"SUPPLEMENT_EVIDENCE\",\"toolRequests\":[{\"requestId\":\"r1\",\"type\":\"SUPPLEMENT_EVIDENCE\",\"evidenceType\":\"DOCUMENT_SEARCH\",\"minimumCount\":1,\"reason\":\"Need facts\"}]}"
                : "{\"status\":\"COMPLETED\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"Risk increased\",\"evidenceIds\":[\"e1\"],\"confidence\":0.8}]}")
            .build());
        AgentExecutionRequest request = request(List.of());
        AgentExecutionOutcome first = adapter.execute(descriptor, request);
        assertThat(first.status()).isEqualTo(AgentExecutionOutcome.Status.SUPPLEMENT_EVIDENCE);
        assertThat(first.metadata()).containsKey("toolRequests");
        var evidence = new ProjectedAnalysisEvidence("e1", AnalysisCapability.DOCUMENT_SEARCH,
            "Approved scoped fact", Map.of());
        AgentExecutionRequest resumed = request(List.of(evidence));
        AgentExecutionOutcome second = adapter.execute(descriptor, resumed);
        assertThat(second.status()).isEqualTo(AgentExecutionOutcome.Status.COMPLETED);
        assertThat(second.claims().get(0).evidenceIds()).containsExactly("e1");
        ArgumentCaptor<AgentRunRequest> runs = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(runtime, times(2)).run(runs.capture());
        assertThat(runs.getAllValues()).allSatisfy(run -> {
            assertThat(run.getAvailableTools()).isEmpty();
            assertThat(run.getMaxToolCalls()).isZero();
            assertThat(run.getBoundDocumentIds()).isEmpty();
        });
        assertThat(runs.getAllValues().get(1).getQuery()).contains("e1", "Approved scoped fact");
        assertThat(runs.getAllValues().get(0).getRunId()).isNotEqualTo(runs.getAllValues().get(1).getRunId());
    }

    @Test void rawExecutableToolRequestIsRejected() {
        SkillCatalogService skills = mock(SkillCatalogService.class);
        SkillDefinition skill = mock(SkillDefinition.class);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(skill.id()).thenReturn("risk");
        when(skill.workflowConfig()).thenReturn(Map.of());
        when(skills.resolve("risk")).thenReturn(skill);
        var adapter = new SkillCatalogAgentComputeAdapter(skills, runtime, mock(AgentRegistryPort.class),
            new ObjectMapper());
        when(runtime.run(any())).thenReturn(AgentRunResult.builder().status(AgentRunStatus.COMPLETED)
            .answer("{\"status\":\"SUPPLEMENT_EVIDENCE\",\"toolRequests\":[{\"requestId\":\"r1\",\"type\":\"SUPPLEMENT_EVIDENCE\",\"evidenceType\":\"DOCUMENT_SEARCH\",\"toolName\":\"sql_query_execute\"}]}")
            .build());
        AgentDescriptor descriptor = new AgentDescriptor("local.skill.risk", "v1", AgentDescriptor.Origin.LOCAL,
            AgentDescriptor.Protocol.LOCAL, null, Set.of(CapabilityId.parse("local.risk.v1")),
            AgentDescriptor.TrustLevel.INTERNAL, AgentDescriptor.DataAccessMode.RUNTIME_MANAGED,
            Set.of(), Set.of(), null, "", 50, true,
            Map.of("supportedExecutionModes", List.of("AGENTIC_EXECUTION")));
        assertThat(adapter.execute(descriptor, request(List.of())).status())
            .isEqualTo(AgentExecutionOutcome.Status.BLOCKED);
    }

    @Test void catalogChangeRefreshesAndRemovesLocalDescriptor() {
        SkillCatalogService skills = mock(SkillCatalogService.class);
        SkillDefinition skill = mock(SkillDefinition.class);
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        when(skill.id()).thenReturn("risk");
        when(skill.workflowConfig()).thenReturn(Map.of());
        when(skills.resolve("risk")).thenReturn(skill);
        var adapter = new SkillCatalogAgentComputeAdapter(skills, mock(AgentRuntime.class), registry,
            new ObjectMapper());

        adapter.onCatalogChange(new SkillCatalogChange("risk", false));
        ArgumentCaptor<AgentDescriptor> published = ArgumentCaptor.forClass(AgentDescriptor.class);
        verify(registry).register(published.capture());
        assertThat(published.getValue().agentId()).isEqualTo("local.skill.risk");
        assertThat(published.getValue().supportsExecutionMode(AgentExecutionMode.AGENTIC_EXECUTION)).isTrue();

        adapter.onCatalogChange(new SkillCatalogChange("risk", true));
        verify(registry).remove("local.skill.risk");
    }

    private AgentExecutionRequest request(List<ProjectedAnalysisEvidence> evidence) {
        return new AgentExecutionRequest(null, "execution-1", CapabilityId.parse("local.risk.v1"),
            new AgentExecutionRequest.TaskContract("ANALYZE", "Assess risk", Map.of()),
            new EvidenceBundle(null, List.copyOf(evidence), List.of(), Map.of()), Set.of(),
            AgentExecutionRequest.Constraints.defaults(), AgentExecutionRequest.OutputContract.defaults(),
            KernelDataScope.system("request-1"),
            Map.of(AgentExecutionRequest.MODE_METADATA_KEY, "AGENTIC_EXECUTION"));
    }
}
