package com.chatchat.knowledgebase.runtime.workflow;

import com.chatchat.common.knowledge.model.KnowledgeIR;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.model.KnowledgeSourceReference;
import com.chatchat.common.knowledge.model.KnowledgeType;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGapReason;
import com.chatchat.common.runtime.analysis.recovery.RecoveryStatus;
import com.chatchat.common.runtime.analysis.recovery.RecoveryStrategy;
import com.chatchat.common.runtime.analysis.recovery.RecoveryLevel;
import com.chatchat.knowledgebase.search.document.api.evidence.DocumentExpandedEvidenceChunk;
import com.chatchat.knowledgebase.search.document.application.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.api.evidence.DocumentSearchExpandRequest;
import com.chatchat.knowledgebase.search.document.api.evidence.DocumentSearchExpandResult;
import com.chatchat.knowledgebase.search.evidence.api.Citation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class KnowledgeEvidenceExpansionWorkflowTest {

    @Test
    void escalatesTruncatedSequenceFromChunkExpansionToAdjacentSectionsByRound() {
        DocumentSearchEvidenceService documents = mock(DocumentSearchEvidenceService.class);
        DocumentExpandedEvidenceChunk recoveredChunk = new DocumentExpandedEvidenceChunk(
            "ref-2", "chunk-2", "doc-1", "install.md", "Install", 2,
            "expanded_evidence", 90D, "continued step", List.of(),
            new Citation("install.md", "Install"), true, null);
        when(documents.expand(org.mockito.ArgumentMatchers.any())).thenReturn(
            new DocumentSearchExpandResult("v1", "install", "doc-1", List.of(recoveredChunk),
                "", List.of(), null, null, null));
        KnowledgeEvidenceExpansionWorkflow workflow = new KnowledgeEvidenceExpansionWorkflow(documents);
        DocumentAnalysisEvidence initial = new DocumentAnalysisEvidence(
            "ref-1", "doc-1", "chunk-1", "install.md", "Install", "install.md#Install",
            "first step", 0.9D, Map.of("truncated", true));
        AnalysisContext context = new AnalysisContext("installation steps",
            new KernelDataScope("tenant", "user", "request", null, "run", null, Map.of()),
            "skill", List.of("doc-1"), List.of(), List.of("SUPER_ADMIN"),
            new AnalysisIntent("INSTALL", List.of(), Set.of(AnalysisCapability.DOCUMENT_SEARCH),
                "UNSPECIFIED", true), Map.of("sequenceSensitive", true));
        EvidenceGap gap = new EvidenceGap(EvidenceGapReason.SOURCE_TRUNCATED, null,
            "doc-1", "Install", 0.5D, 0.8D, true, true, List.of("missing continuation"));

        var first = workflow.recover(context,
            new EvidenceBundle(null, List.of(initial), List.of(), Map.of("sourceTruncated", true)), gap, 1);
        var third = workflow.recover(context,
            new EvidenceBundle(null, List.of(initial), List.of(), Map.of("sourceTruncated", true)), gap, 3);

        ArgumentCaptor<DocumentSearchExpandRequest> requests = ArgumentCaptor.forClass(DocumentSearchExpandRequest.class);
        verify(documents, times(2)).expand(requests.capture());
        assertThat(first.level()).isEqualTo(RecoveryLevel.L1_CHUNK_EXPANSION);
        assertThat(first.metadata()).containsEntry("recoveryComplete", false);
        assertThat(requests.getAllValues().get(0).maxChunks()).isEqualTo(4);
        assertThat(third.level()).isEqualTo(RecoveryLevel.L3_ADJACENT_SECTION);
        assertThat(third.metadata()).containsEntry("recoveryComplete", true);
        assertThat(requests.getAllValues().get(1).maxSections()).isEqualTo(5);
    }

    @Test
    void followsAdjacentSectionStrategyForSequenceGapWithoutRestartingGlobalSearch() {
        DocumentSearchEvidenceService documents = mock(DocumentSearchEvidenceService.class);
        DocumentExpandedEvidenceChunk expandedChunk = new DocumentExpandedEvidenceChunk(
            "ref-7", "chunk-7", "doc-1", "install.md", "3.1.1.7", 7,
            "expanded_evidence", 93D, "next installation step", List.of("step"),
            new Citation("install.md", "3.1.1.7"), true, null);
        when(documents.expand(org.mockito.ArgumentMatchers.any())).thenReturn(
            new DocumentSearchExpandResult("v1", "install", "doc-1", List.of(expandedChunk),
                "", List.of(), null, null, null));
        KnowledgeEvidenceExpansionWorkflow workflow = new KnowledgeEvidenceExpansionWorkflow(documents);
        DocumentAnalysisEvidence initial = new DocumentAnalysisEvidence(
            "ref-6", "doc-1", "chunk-6", "install.md", "3.1.1.6", "install.md#3.1.1.6",
            "partial step", 0.9D, Map.of("truncated", true));
        AnalysisContext context = new AnalysisContext("获取安装步骤",
            new KernelDataScope("tenant", "user", "request", null, "run", null, Map.of()),
            "skill", List.of("doc-1"), List.of(), List.of("SUPER_ADMIN"),
            new AnalysisIntent("INSTALL", List.of(), Set.of(AnalysisCapability.DOCUMENT_SEARCH),
                "UNSPECIFIED", true), Map.of());
        EvidenceGap gap = new EvidenceGap(EvidenceGapReason.SEQUENCE_INCOMPLETE, null,
            "doc-1", "3.1.1.6", 0.5D, 0.8D, true, true, List.of("missing next step"));

        var result = workflow.recover(context,
            new EvidenceBundle(null, List.of(initial), List.of(), Map.of("sourceTruncated", true)), gap, 1);

        ArgumentCaptor<DocumentSearchExpandRequest> captor = ArgumentCaptor.forClass(DocumentSearchExpandRequest.class);
        verify(documents).expand(captor.capture());
        assertThat(captor.getValue().docId()).isEqualTo("doc-1");
        assertThat(captor.getValue().sections()).containsExactly("3.1.1.6");
        assertThat(captor.getValue().maxSections()).isEqualTo(5);
        assertThat(result.strategy()).isEqualTo(RecoveryStrategy.ADJACENT_SECTION_SEARCH);
        assertThat(result.level()).isEqualTo(RecoveryLevel.L3_ADJACENT_SECTION);
        assertThat(result.status()).isEqualTo(RecoveryStatus.RETRY_REQUIRED);
        assertThat(result.evidence().evidence()).hasSize(2);
        assertThat(result.evidence().metadata())
            .containsEntry("recoveryComplete", true)
            .containsEntry("sequenceComplete", true)
            .containsEntry("sourceTruncated", false);
    }

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
