package com.chatchat.mcpserver.document;

import com.chatchat.agents.tool.DefaultToolRegistry;
import com.chatchat.agents.tool.ToolRegistry;
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
    void registersLocalCapabilityAndCallsKnowledgeBaseDirectly() {
        DocumentSearchEvidenceService evidenceService = mock(DocumentSearchEvidenceService.class);
        DocumentSearchResult expected = mock(DocumentSearchResult.class);
        when(evidenceService.search(org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        DocumentSearchMcpToolRegistrar registrar = new DocumentSearchMcpToolRegistrar(
            evidenceService,
            new DocumentSearchRequestMapper(),
            new MockEnvironment().withProperty("chatchat.mcp.server.document-search.default-limit", "6")
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
        assertThat(provenance.sourceRef()).isEqualTo("knowledge-base://document-index");
        assertThat(provenance.inputFingerprint()).startsWith("sha256:");
        ArgumentCaptor<DocumentSearchRequest> request = ArgumentCaptor.forClass(DocumentSearchRequest.class);
        verify(evidenceService).search(request.capture());
        assertThat(request.getValue().query()).isEqualTo("portfolio valuation source");
        assertThat(request.getValue().topK()).isEqualTo(6);
        assertThat(request.getValue().fileIds()).containsExactly("doc-1");
        assertThat(request.getValue().tenantId()).isEqualTo("tenant-a");
    }
}
