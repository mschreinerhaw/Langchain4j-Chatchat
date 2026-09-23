package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.runtime.index.KnowledgeIREntity;
import com.chatchat.knowledgebase.runtime.index.KnowledgeIRRepository;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.model.SearchResult;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParentSectionExpansionStageTest {
    @Test
    void loadsPostgresSectionNavigationForRerankedDocuments() {
        KnowledgeIRRepository repository = mock(KnowledgeIRRepository.class);
        KnowledgeIREntity entity = new KnowledgeIREntity();
        entity.setDocumentId("doc-1");
        entity.setKnowledgeId("unit-1");
        entity.setSourceChunkId("chunk-1");
        entity.setSourceSection("Installation");
        when(repository.findByDocumentIdInAndActiveTrue(List.of("doc-1"))).thenReturn(List.of(entity));
        SearchResult result = mock(SearchResult.class);
        when(result.docId()).thenReturn("doc-1");
        DocumentRetrievalWorkflowContext context = new DocumentRetrievalWorkflowContext(plan(), 8);
        context.candidates(List.of(new DocumentSearchCandidate(result, 1, 0, false, true)));

        new ParentSectionExpansionStage(repository, new SearchProperties()).execute(context);

        assertThat(context.parentSections().get("doc-1"))
            .extracting(DocumentParentSection::section).containsExactly("Installation");
    }

    private DocumentSearchPlan plan() {
        return new DocumentSearchPlan("livedata install", 8, null, List.of(), List.of(), List.of(),
            "", "how_to", List.of(), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of()),
            DocumentVisibilityContext.unrestricted(), null);
    }
}
