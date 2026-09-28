package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.runtime.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.skill.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.skill.KnowledgeSkillType;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import com.chatchat.knowledgebase.search.document.application.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentKnowledgeSkillExecutorTest {

    @Test
    void usesAllBoundTagsAndDerivesTopKFromTheSkillBudget() {
        DocumentSearchEvidenceService search = mock(DocumentSearchEvidenceService.class);
        when(search.search(org.mockito.ArgumentMatchers.any())).thenReturn(
            new DocumentSearchResult("v1", "query", "intent", 0, List.of(), "", List.of()));
        DocumentKnowledgeSkillExecutor executor = new DocumentKnowledgeSkillExecutor(search);
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "usage", KnowledgeSkillType.CONCEPT_LOOKUP, "product", "了解产品用途",
            List.of(), 1, 901, Map.of());
        KnowledgeRequest request = new KnowledgeRequest(
            "v", "这张表能做什么", "ROLE_CHAT", 1200,
            new KnowledgeScope("advisor", "tenant", "user", List.of("doc-1"),
                List.of("两融", "股票期权"), List.of()), null, Map.of());

        executor.execute(new KnowledgeSkillExecutionContext(request, skill));

        ArgumentCaptor<DocumentSearchRequest> captured = ArgumentCaptor.forClass(DocumentSearchRequest.class);
        verify(search).search(captured.capture());
        assertThat(captured.getValue().topK()).isEqualTo(4);
        assertThat(captured.getValue().filters().allTags()).containsExactly("两融", "股票期权");
    }

    @Test
    void propagatesRecoveryFailureIndependentlyOfUsableEvidence() {
        DocumentSearchEvidenceService search = mock(DocumentSearchEvidenceService.class);
        AnalysisRuntimePort runtime = mock(AnalysisRuntimePort.class);
        DocumentKnowledgeSkillExecutor executor = new DocumentKnowledgeSkillExecutor(search);
        ReflectionTestUtils.setField(executor, "analysisRuntime", runtime);
        DocumentAnalysisEvidence evidence = new DocumentAnalysisEvidence(
            "ref-1", "doc-1", "chunk-1", "install.md", "Install", "install.md#Install",
            "verified step", 0.9D, Map.of("recovered", true));
        when(runtime.analyze(org.mockito.ArgumentMatchers.any())).thenReturn(new AnalysisExecutionOutcome(
            null, AnalysisWorkflowType.DOCUMENT, null,
            new VerificationResult(true, List.of(evidence), List.of("next step is still missing")),
            new EvidenceBundle(null, List.of(evidence), List.of(), Map.of()), "",
            Map.of("evidenceRecoveryStatus", "FAILED", "evidenceState",
                Map.of("transportState", "PARTIALLY_AVAILABLE", "evidenceCount", 1))));
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "install", KnowledgeSkillType.PROCEDURE_LOOKUP, "ops", "installation",
            List.of(), 1, 900, Map.of());
        KnowledgeRequest request = new KnowledgeRequest(
            "v", "installation steps", "PROCEDURE", 1200,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc-1"),
                List.of(), List.of("SUPER_ADMIN")), null, Map.of());

        var result = executor.execute(new KnowledgeSkillExecutionContext(request, skill));

        assertThat(result.status()).isEqualTo("used");
        assertThat(result.knowledgeUnits()).singleElement().satisfies(unit ->
            assertThat(unit.compactPromptRepresentation()).contains("verified step"));
        assertThat(result.metadata()).containsEntry("evidenceRecoveryStatus", "FAILED")
            .containsEntry("evidenceState", Map.of("transportState", "PARTIALLY_AVAILABLE", "evidenceCount", 1))
            .doesNotContainKey("evidenceTerminalState");
    }
}
