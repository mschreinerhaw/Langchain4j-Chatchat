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
import com.chatchat.knowledgebase.search.document.DocumentSearchHit;
import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class DocumentSearchMcpToolRegistrarTest {

    @Test
    void retriesWithOnlyOneExpansionTermAfterEmptyOriginalQuery() {
        DocumentSearchEvidenceService evidenceService = mock(DocumentSearchEvidenceService.class);
        DocumentSearchResult empty = new DocumentSearchResult("document_evidence_v1", "服务器安装", "how_to",
            0, List.of(), "", List.of());
        DocumentSearchResult found = new DocumentSearchResult("document_evidence_v1", "服务器安装 server", "how_to",
            1, List.of(), "", List.of(), null, null, List.of(), null, null,
            List.of(new DocumentSearchHit("doc-1", "服务器安装", "server.md", "markdown", 90D, List.of())),
            List.of(), null, null, null, null);
        when(evidenceService.search(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            DocumentSearchRequest request = invocation.getArgument(0);
            return request.query().contains("server") ? found : empty;
        });
        com.chatchat.knowledgebase.search.query.QueryExpander expander =
            mock(com.chatchat.knowledgebase.search.query.QueryExpander.class);
        when(expander.expandQuery("服务器安装")).thenReturn(List.of("服务器安装", "server", "host"));
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        when(authorization.currentCallerContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext("tenant-a", "user-a", "caller", List.of()));
        DocumentEvidenceAuthorizationFilter filter = mock(DocumentEvidenceAuthorizationFilter.class);
        when(filter.filter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        DefaultToolRegistry registry = new DefaultToolRegistry();
        new DocumentSearchMcpToolRegistrar(evidenceService, new DocumentSearchRequestMapper(),
            new MockEnvironment(), mock(ApiDocumentEvidenceClient.class),
            new FederatedDocumentEvidenceSelector(new com.chatchat.knowledgebase.search.query.SearchTokenizer(),
                new com.chatchat.knowledgebase.search.evidence.EvidenceContextFormatter()),
            filter, authorization, expander, new com.chatchat.knowledgebase.search.query.SearchTokenizer())
            .registerTools(registry);

        ToolOutput output = registry.getEnhancedTool(DocumentSearchMcpToolRegistrar.TOOL_NAME)
            .execute(ToolInput.builder().parameters(Map.of("query", "服务器安装")).build());

        assertThat(output.isSuccess()).isTrue();
        ArgumentCaptor<DocumentSearchRequest> requests = ArgumentCaptor.forClass(DocumentSearchRequest.class);
        verify(evidenceService, times(2)).search(requests.capture());
        assertThat(requests.getAllValues().get(0).query()).isEqualTo("服务器安装");
        assertThat(requests.getAllValues().get(1).query()).isEqualTo("服务器安装 server");
    }

    @Test
    void titleOnlyHitIsExpandedWithOriginalQueryAndAuthorizedDocumentScope() {
        DocumentSearchEvidenceService evidenceService = mock(DocumentSearchEvidenceService.class);
        DocumentSearchHit hit = new DocumentSearchHit("doc-live", "LiveData installation", "LiveData.md",
            "markdown", 90D, List.of());
        DocumentSearchResult titleOnly = new DocumentSearchResult("document_evidence_v1", "livedata 安装说明",
            "how_to", 1, List.of(), "", List.of(), null, null, List.of(), null, null,
            List.of(hit), List.of(), null, null, null, null);
        DocumentEvidenceChunk chunk = new DocumentEvidenceChunk("doc-live:1", "1", "doc-live", "LiveData.md",
            "安装", 1, "TEXT", 90D, "安装 LiveData 的第一步", List.of(), null,
            null, "tenant-a", "user-a", "tenant", List.of());
        DocumentSearchResult withBody = new DocumentSearchResult("document_evidence_v1", "livedata 安装说明",
            "how_to", 1, List.of(chunk), "安装 LiveData 的第一步", List.of());
        when(evidenceService.search(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            DocumentSearchRequest request = invocation.getArgument(0);
            return request.fileIds() == null || request.fileIds().isEmpty() ? titleOnly : withBody;
        });
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        when(authorization.currentCallerContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext("tenant-a", "user-a", "caller", List.of()));
        DocumentEvidenceAuthorizationFilter filter = mock(DocumentEvidenceAuthorizationFilter.class);
        when(filter.filter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        DefaultToolRegistry registry = new DefaultToolRegistry();
        new DocumentSearchMcpToolRegistrar(evidenceService, new DocumentSearchRequestMapper(),
            new MockEnvironment(), mock(ApiDocumentEvidenceClient.class),
            new FederatedDocumentEvidenceSelector(new com.chatchat.knowledgebase.search.query.SearchTokenizer(),
                new com.chatchat.knowledgebase.search.evidence.EvidenceContextFormatter()),
            filter, authorization, mock(com.chatchat.knowledgebase.search.query.QueryExpander.class),
            new com.chatchat.knowledgebase.search.query.SearchTokenizer()).registerTools(registry);

        ToolOutput output = registry.getEnhancedTool(DocumentSearchMcpToolRegistrar.TOOL_NAME)
            .execute(ToolInput.builder().parameters(Map.of("query", "livedata 安装说明")).build());

        assertThat(output.isSuccess()).isTrue();
        DocumentSearchResult result = (DocumentSearchResult) output.getData();
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).content()).contains("安装 LiveData");
        ArgumentCaptor<DocumentSearchRequest> requests = ArgumentCaptor.forClass(DocumentSearchRequest.class);
        verify(evidenceService, times(2)).search(requests.capture());
        assertThat(requests.getAllValues().get(0).query()).isEqualTo("livedata 安装说明");
        assertThat(requests.getAllValues().get(1).query()).isEqualTo("livedata 安装说明");
        assertThat(requests.getAllValues().get(1).fileIds()).containsExactly("doc-live");
    }

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
            evidenceFilter, authorization, mock(com.chatchat.knowledgebase.search.query.QueryExpander.class),
            new com.chatchat.knowledgebase.search.query.SearchTokenizer()).registerTools(registry);

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
            authorization,
            mock(com.chatchat.knowledgebase.search.query.QueryExpander.class),
            new com.chatchat.knowledgebase.search.query.SearchTokenizer()
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
