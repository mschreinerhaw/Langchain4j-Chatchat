package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeScope;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.KnowledgeSkillSynthesizerPort;
import com.chatchat.common.knowledge.KnowledgeSkillType;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentKnowledgeRuntimeServiceTest {

    @Test
    void executesPlannedKnowledgeSkillInsideScopeAndCompilesToBudget() {
        DocumentSearchEvidenceService search = mock(DocumentSearchEvidenceService.class);
        KnowledgeSkillSynthesizerPort planner = mock(KnowledgeSkillSynthesizerPort.class);
        KnowledgeSkillInstance skill = new KnowledgeSkillInstance(
            "rule-1", KnowledgeSkillType.RULE_LOOKUP, "securities.risk",
            "获取集中度判断规则", List.of("单票占比"), 1, 50, Map.of());
        when(planner.synthesize(any())).thenReturn(new KnowledgeSkillPlan(
            "v", "RISK", List.of(skill), 50));
        when(search.search(any())).thenReturn(new DocumentSearchResult(
            "v1", "rule", "knowledge", 1, List.of(),
            "集中度风险需要结合单票占比、Top3占比、行业集中度及客户风险承受能力综合判断，不能把示例数字作为客户事实。", List.of()));
        DocumentKnowledgeSkillExecutor executor = new DocumentKnowledgeSkillExecutor(search);
        DefaultKnowledgeRuntimeService runtime = new DefaultKnowledgeRuntimeService(
            planner, List.of(executor), new BudgetedKnowledgeContextCompiler());
        KnowledgeRequest request = new KnowledgeRequest(
            "v", "分析客户集中度风险", "RISK", 50,
            new KnowledgeScope("agent", "tenant-a", "user-a", List.of("doc-policy"),
                List.of("risk"), List.of("securities.risk")), null, Map.of());

        var result = runtime.retrieveKnowledge(request);

        ArgumentCaptor<DocumentSearchRequest> searchRequest = ArgumentCaptor.forClass(DocumentSearchRequest.class);
        verify(search).search(searchRequest.capture());
        assertThat(searchRequest.getValue().selectedDocumentIds()).containsExactly("doc-policy");
        assertThat(searchRequest.getValue().documentVisibilityEnforced()).isTrue();
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(50);
        assertThat(result.truncated()).isTrue();
        assertThat(result.compiledContext()).doesNotContain("示例数字作为客户事实");
    }
}
