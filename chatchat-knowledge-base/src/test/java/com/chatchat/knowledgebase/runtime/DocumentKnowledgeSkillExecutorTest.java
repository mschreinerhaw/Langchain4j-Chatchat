package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeScope;
import com.chatchat.common.knowledge.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillType;
import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
}
