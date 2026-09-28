package com.chatchat.knowledgebase.runtime.workflow;

import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.model.KnowledgeSourceReference;
import com.chatchat.common.knowledge.model.KnowledgeType;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.knowledgebase.search.document.DocumentExpandedEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchExpandRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchExpandResult;
import com.chatchat.knowledgebase.search.evidence.Citation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeEvidenceExpansionWorkflowTest {

    @Test
    void expandsTheAuthorizedDocumentAndSectionSelectedByInitialRetrieval() {
        DocumentSearchEvidenceService documents = mock(DocumentSearchEvidenceService.class);
        DocumentExpandedEvidenceChunk expandedChunk = new DocumentExpandedEvidenceChunk(
            "ref-2", "chunk-2", "doc-1", "install.md", "Linux installation", 2,
            "expanded_evidence", 91D, "step one\nstep two", List.of("step"),
            new Citation("install.md", "Linux installation"), true, null);
        when(documents.expand(org.mockito.ArgumentMatchers.any())).thenReturn(
            new DocumentSearchExpandResult("v1", "install", "doc-1", List.of(expandedChunk),
                "", List.of(), null, null, null));
        KnowledgeEvidenceExpansionWorkflow workflow = new KnowledgeEvidenceExpansionWorkflow(documents);
        KnowledgeIR initial = new KnowledgeIR(
            "chunk-1", "ops", KnowledgeType.PROCEDURE, "Linux installation", "partial",
            List.of(), List.of(), List.of("PROCEDURE"), List.of(), "partial",
            new KnowledgeSourceReference("ref-1", "doc-1", "chunk-1", "install.md",
                "Linux installation", null, "install.md#Linux installation"), 0.9D);
        KnowledgeRequest request = new KnowledgeRequest(
            "v", "install", "PROCEDURE", 1_500,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc-1"), List.of(),
                List.of(), List.of("SUPER_ADMIN")), null, Map.of());

        KnowledgeEvidenceExpansionWorkflow.ExpansionResult result = workflow.expand(request, List.of(initial));

        ArgumentCaptor<DocumentSearchExpandRequest> captor = ArgumentCaptor.forClass(DocumentSearchExpandRequest.class);
        verify(documents).expand(captor.capture());
        assertThat(captor.getValue().docId()).isEqualTo("doc-1");
        assertThat(captor.getValue().sections()).containsExactly("Linux installation");
        assertThat(captor.getValue().selectedDocumentIds()).containsExactly("doc-1");
        assertThat(captor.getValue().documentVisibilityEnforced()).isTrue();
        assertThat(captor.getValue().roles()).containsExactly("SUPER_ADMIN");
        assertThat(result.complete()).isTrue();
        assertThat(result.units()).singleElement().satisfies(unit -> {
            assertThat(unit.compactPromptRepresentation()).contains("step one", "step two");
            assertThat(unit.source().documentId()).isEqualTo("doc-1");
            assertThat(unit.source().section()).isEqualTo("Linux installation");
        });
    }
}
