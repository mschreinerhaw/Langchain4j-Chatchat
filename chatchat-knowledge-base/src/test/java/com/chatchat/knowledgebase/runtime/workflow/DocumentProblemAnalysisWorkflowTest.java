package com.chatchat.knowledgebase.runtime.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentProblemAnalysisWorkflowTest {
    @Test
    void executesDocumentPipelineAndReturnsTypedEvidence() {
        DocumentSearchEvidenceService documents = mock(DocumentSearchEvidenceService.class);
        DocumentEvidenceChunk chunk = new DocumentEvidenceChunk(
            "ref-1", "chunk-1", "doc-1", "LiveData installation", "Install", 3,
            "TEXT", 0.92D, "Run the installation command", List.of(), null, null,
            "tenant-1", "user-1", "PRIVATE", List.of("developer"));
        when(documents.search(org.mockito.ArgumentMatchers.any())).thenReturn(new DocumentSearchResult(
            "v1", "LiveData install", "DOCUMENT_SEARCH", 1, List.of(chunk),
            "Run the installation command", List.of()));
        DocumentProblemAnalysisWorkflow workflow = new DocumentProblemAnalysisWorkflow(documents);
        KernelDataScope kernelScope = new KernelDataScope(
            "tenant-1", "user-1", "request-1", null, null, null, Map.of());
        AnalysisContext context = new AnalysisContext("LiveData install", kernelScope, "skill-1",
            List.of("doc-1"), List.of("manual"), List.of("developer"),
            new AnalysisIntent("DOCUMENT_INSTALL", List.of(),
                Set.of(AnalysisCapability.DOCUMENT_SEARCH), "CURRENT", true),
            Map.of("topK", 5));

        AnalysisExecutionOutcome result = workflow.execute(context, kernelScope);

        assertThat(result.plan().steps()).extracting(step -> step.operation()).containsExactly(
            "QUERY_ANALYZE", "SKILL_ROLE_CONTEXT", "POSTGRES_DOCUMENT_ROUTE",
            "OPENSEARCH_HYBRID_RETRIEVE", "RRF_FUSION", "BGE_RERANK",
            "POSTGRES_PARENT_SECTION", "ROCKSDB_SOURCE_VERIFY", "EVIDENCE_BUNDLE");
        assertThat(result.verification().accepted()).isTrue();
        assertThat(result.evidenceBundle().evidence()).singleElement()
            .isInstanceOf(DocumentAnalysisEvidence.class);
        assertThat(result.synthesis()).isEqualTo("Run the installation command");

        ArgumentCaptor<DocumentSearchRequest> request = ArgumentCaptor.forClass(DocumentSearchRequest.class);
        verify(documents).search(request.capture());
        assertThat(request.getValue().selectedDocumentIds()).containsExactly("doc-1");
        assertThat(request.getValue().filters().tags()).containsExactly("manual");
    }
}
