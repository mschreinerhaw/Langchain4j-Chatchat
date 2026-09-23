package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BgeDocumentCandidateRerankerTest {
    @Test
    void disabledRemoteRerankerStillEnforcesFinalEvidenceBudget() {
        SearchProperties properties = new SearchProperties();
        properties.getProblemAnalysis().setFinalEvidenceLimit(5);
        List<DocumentSearchCandidate> candidates = IntStream.range(0, 9)
            .mapToObj(ignored -> mock(DocumentSearchCandidate.class)).toList();

        List<DocumentSearchCandidate> ranked = new BgeDocumentCandidateReranker(properties, new ObjectMapper())
            .rerank(plan(), candidates);

        assertThat(ranked).containsExactlyElementsOf(candidates.subList(0, 5));
    }

    private DocumentSearchPlan plan() {
        return new DocumentSearchPlan("livedata install", 8, null, List.of(), List.of(), List.of(),
            "", "how_to", List.of(), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of()),
            DocumentVisibilityContext.unrestricted(), null);
    }
}
