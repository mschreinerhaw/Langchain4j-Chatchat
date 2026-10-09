package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.evidence.*;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GovernedExecutionTraceRetrievalTest {
    EvidenceStorePort store = mock(EvidenceStorePort.class);
    SkillExecutionScopePort scopes = mock(SkillExecutionScopePort.class);
    SkillCatalogService skills = mock(SkillCatalogService.class);
    McpToolCatalogQueryPort catalog = mock(McpToolCatalogQueryPort.class);
    ToolRegistry tools = mock(ToolRegistry.class);
    EnterpriseToolRuntimePolicyProvider policy = mock(EnterpriseToolRuntimePolicyProvider.class);
    KernelDataScope scope = new KernelDataScope("tenant", "user", null, null, "run", null, Map.of());
    GovernedExecutionTraceRetrieval traces = new GovernedExecutionTraceRetrieval(store, scopes, skills, catalog, tools, policy, new ObjectMapper());
    ToolMetadata metadata = ToolMetadata.builder().id("tool").operationType("read").build();
    SkillDefinition skill = mock(SkillDefinition.class);

    @BeforeEach void admitted() {
        when(skill.id()).thenReturn("skill");
        when(skill.boundMcpToolNames()).thenReturn(List.of("tool"));
        when(scopes.resolve(anyString(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of("doc"), List.of(), List.of(), true, true));
        when(skills.list()).thenReturn(List.of(skill));
        when(catalog.registeredTools()).thenReturn(List.of());
        when(tools.getToolMetadata("tool")).thenReturn(metadata);
        when(tools.getToolRevision("tool")).thenReturn(7L);
        when(policy.resolve(any(), any())).thenReturn(ToolRuntimePolicy.builder().allowed(true).build());
        when(store.find(scope, "e1")).thenReturn(Optional.of(record("TOOL_CALL")));
        when(store.lineage(scope, "e1")).thenReturn(Optional.empty());
        when(store.query(any())).thenReturn(List.of(record("TOOL_CALL")));
    }

    @Test void summaryFirstProjectionRedactsSecretsAndNeverExposesInvocationArguments() {
        var found = traces.search(scope, "rows", 99);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).excerpt()).hasSizeLessThanOrEqualTo(120);
        var detail = traces.get(scope, "e1").orElseThrow();
        String content = detail.observation().toString();
        assertThat(content).contains("42", "[redacted]").doesNotContain("sensitive-value", "hidden-prompt", "authorizationParameters");
        assertThat(detail.trustBoundary()).isEqualTo("UNTRUSTED_OBSERVATION_REQUIRES_CURRENT_VERIFICATION");
    }

    @Test void currentSkillRevocationAppliesToSearchAndDetails() {
        when(scopes.resolve(anyString(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(SkillExecutionScopePort.EffectiveScope.denied(List.of()));
        assertThat(traces.search(scope, "rows", 10)).isEmpty();
        assertThat(traces.get(scope, "e1")).isEmpty();
        verify(policy, never()).resolve(any(), any());
    }

    @Test void currentToolPermissionRevocationAndContractChangesRejectOldObservations() {
        when(policy.resolve(any(), any())).thenReturn(ToolRuntimePolicy.builder().allowed(false).build());
        assertThat(traces.get(scope, "e1")).isEmpty();
        when(policy.resolve(any(), any())).thenReturn(ToolRuntimePolicy.builder().allowed(true).build());
        metadata.setVersion("2.0.0");
        assertThat(traces.get(scope, "e1")).isEmpty();
        metadata.setVersion("1.0.0");
        metadata.setPublicationStatus("disabled");
        assertThat(traces.get(scope, "e1")).isEmpty();
    }

    @Test void unboundToolAndUnknownEvidenceTypesFailClosed() {
        when(skill.boundMcpToolNames()).thenReturn(List.of());
        assertThat(traces.get(scope, "e1")).isEmpty();
        when(store.find(scope, "e1")).thenReturn(Optional.of(record("COMPUTATION")));
        assertThat(traces.get(scope, "e1")).isEmpty();
    }

    @Test void documentAccessIsRevalidatedAndDeniedDocumentsStayHidden() {
        when(store.find(scope, "e1")).thenReturn(Optional.of(record("DOCUMENT_SEARCH")));
        assertThat(traces.get(scope, "e1")).isPresent();
        when(scopes.resolve(anyString(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), true, true));
        assertThat(traces.get(scope, "e1")).isEmpty();
    }
    private EvidenceRecord record(String type) {
        return new EvidenceRecord(null, "e1", scope, type, "worker", "hash",
            Map.of("content", "{\"rows\":[42],\"accessToken\":\"sensitive-value\",\"system_prompt\":\"hidden-prompt\"}",
                "attributes", Map.of("toolRevision", 7L,
                    "toolContractHash", AnalysisToolContractIdentity.fingerprint(metadata, new ObjectMapper()),
                    "authorizationParameters", Map.of("domain", "dataset"))),
            null, 100, Map.of("skillId", "skill", "toolName", "tool", "documentId", "doc", "verified", true));
    }
    @Test void processLocalRegistryCounterChangesDoNotInvalidateAnUnchangedPublishedContract() {
        when(tools.getToolRevision("tool")).thenReturn(500L);
        assertThat(traces.get(scope, "e1")).isPresent();
    }
    @Test void structuredQueriesRetainTheirToolAuthorizationAndDerivedResultsRequireAllParents() {
        var source = record("STRUCTURED_DATA");
        var parent = new EvidenceRecord(null, "parent", source.scope(), source.evidenceType(), source.sourceNode(),
            source.contentSha256(), source.payload(), null, source.occurredAtEpochMs(), source.metadata());
        when(store.find(scope, "parent")).thenReturn(Optional.of(parent));
        when(store.find(scope, "e1")).thenReturn(Optional.of(record("COMPUTATION")));
        when(store.lineage(scope, "e1")).thenReturn(Optional.of(new EvidenceLineage(null, "e1", scope,
            List.of("parent"), "run", "compute", null, Map.of())));
        assertThat(traces.get(scope, "e1")).isPresent();
        when(policy.resolve(any(), any())).thenReturn(ToolRuntimePolicy.builder().allowed(false).build());
        assertThat(traces.get(scope, "e1")).isEmpty();
    }
    @Test void cyclicDerivedTracesAreRejectedInsteadOfRecursingIndefinitely() {
        when(store.find(scope, "e1")).thenReturn(Optional.of(record("COMPUTATION")));
        when(store.lineage(scope, "e1")).thenReturn(Optional.of(new EvidenceLineage(null, "e1", scope,
            List.of("e1"), "run", "compute", null, Map.of())));
        assertThat(traces.get(scope, "e1")).isEmpty();
    }
}
