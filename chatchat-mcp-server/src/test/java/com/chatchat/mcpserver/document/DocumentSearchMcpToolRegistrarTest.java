package com.chatchat.mcpserver.document;

import com.chatchat.agents.tool.DefaultToolRegistry;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.common.mcp.service.McpResultKind;
import com.chatchat.common.mcp.service.McpResultProvenance;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentSearchMcpToolRegistrarTest {

    @Test
    void failsClosedWhenAuthorizationServiceIsUnavailable() {
        DocumentSearchEvidenceService evidenceService = mock(DocumentSearchEvidenceService.class);
        DocumentSearchResult result = new DocumentSearchResult("document_evidence_v1", "query", "", 0,
            List.of(), "", List.of());
        when(evidenceService.search(org.mockito.ArgumentMatchers.any())).thenReturn(result);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        when(authorization.currentCallerContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext("tenant-a", "user-a", "caller", List.of()));
        DocumentEvidenceAuthorizationFilter evidenceFilter = mock(DocumentEvidenceAuthorizationFilter.class);
        when(evidenceFilter.filter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("API unavailable"));
        DefaultToolRegistry registry = new DefaultToolRegistry();
        new DocumentSearchMcpToolRegistrar(evidenceService, new DocumentSearchRequestMapper(),
            new MockEnvironment(), mock(ApiDocumentEvidenceClient.class),
            new FederatedDocumentEvidenceSelector(new com.chatchat.knowledgebase.search.query.SearchTokenizer(),
                new com.chatchat.knowledgebase.search.evidence.EvidenceContextFormatter()),
            evidenceFilter, authorization).registerTools(registry);

        ToolOutput output = registry.getEnhancedTool(DocumentSearchMcpToolRegistrar.TOOL_NAME)
            .execute(ToolInput.builder().parameters(Map.of("query", "query")).build());
        assertThat(output.isSuccess()).isFalse();
        assertThat(output.getData()).isNull();
    }

    @Test
    void registersLocalCapabilityAndCallsKnowledgeBaseDirectly() {
        DocumentSearchEvidenceService evidenceService = mock(DocumentSearchEvidenceService.class);
        DocumentSearchResult expected = mock(DocumentSearchResult.class);
        when(evidenceService.search(org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        when(authorization.currentCallerContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext("tenant-a", "user-a", "caller", List.of("reader")));
        DocumentEvidenceAuthorizationFilter evidenceFilter = mock(DocumentEvidenceAuthorizationFilter.class);
        when(evidenceFilter.filter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        DocumentSearchMcpToolRegistrar registrar = new DocumentSearchMcpToolRegistrar(
            evidenceService,
            new DocumentSearchRequestMapper(),
            new MockEnvironment().withProperty("chatchat.mcp.server.document-search.default-limit", "6"),
            mock(ApiDocumentEvidenceClient.class),
            new FederatedDocumentEvidenceSelector(new com.chatchat.knowledgebase.search.query.SearchTokenizer(),
                new com.chatchat.knowledgebase.search.evidence.EvidenceContextFormatter()),
            evidenceFilter,
            authorization
        );

        registrar.registerTools(registry);
        ToolRegistry.EnhancedTool tool = registry.getEnhancedTool(DocumentSearchMcpToolRegistrar.TOOL_NAME);
        ToolOutput output = tool.execute(ToolInput.builder().parameters(Map.of(
            "query", "portfolio valuation source",
            "fileIds", List.of("doc-1"),
            "tenantId", "tenant-a"
        )).build());

        assertThat(output.isSuccess()).isTrue();
        assertThat(output.getData()).isSameAs(expected);
        assertThat(output.getMetadata()).containsEntry(
            McpServiceResult.RESULT_KIND_KEY, McpResultKind.DOCUMENT.name());
        assertThat(output.getMetadata()).containsEntry(
            McpServiceResult.RESULT_SCHEMA_REF_KEY, "document_evidence_v1");
        McpResultProvenance provenance = (McpResultProvenance) output.getMetadata()
            .get(McpServiceResult.PROVENANCE_KEY);
        assertThat(provenance.sourceRef()).isEqualTo("knowledge-base://mcp-document-index");
        assertThat(provenance.inputFingerprint()).startsWith("sha256:");
        ArgumentCaptor<DocumentSearchRequest> request = ArgumentCaptor.forClass(DocumentSearchRequest.class);
        verify(evidenceService).search(request.capture());
        assertThat(request.getValue().query()).isEqualTo("portfolio valuation source");
        assertThat(request.getValue().topK()).isEqualTo(6);
        assertThat(request.getValue().fileIds()).containsExactly("doc-1");
        assertThat(request.getValue().tenantId()).isEqualTo("tenant-a");
        assertThat(request.getValue().userId()).isEqualTo("user-a");
        assertThat(request.getValue().roles()).containsExactly("reader");
    }
}
