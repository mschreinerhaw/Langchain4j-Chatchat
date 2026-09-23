package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.index.PerDocumentIndexService;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.model.SearchMatchedChunk;
import com.chatchat.knowledgebase.search.model.SearchResult;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceVerificationStageTest {
    @Test
    void keepsOnlyCandidatesWhoseCurrentSourceContainsTheIndexedPassage() {
        PerDocumentIndexService documents = mock(PerDocumentIndexService.class);
        when(documents.openDocumentIndex(eq("valid"), any())).thenReturn(Optional.of(
            SearchDocument.builder().docId("valid").version(2)
                .content("Install LiveData with the documented command.").build()));
        when(documents.openDocumentIndex(eq("stale"), any())).thenReturn(Optional.of(
            SearchDocument.builder().docId("stale").version(2)
                .content("The source was replaced.").build()));
        DocumentRetrievalWorkflowContext context = new DocumentRetrievalWorkflowContext(plan(), 8);
        context.candidates(List.of(candidate("valid"), candidate("stale")));

        new SourceVerificationStage(documents).execute(context);

        assertThat(context.candidates()).extracting(item -> item.result().docId()).containsExactly("valid");
        assertThat(context.verifiedSources()).containsOnlyKeys("valid");
    }

    private DocumentSearchCandidate candidate(String id) {
        SearchMatchedChunk chunk = new SearchMatchedChunk(id, id + ".md", "Install", "content",
            id + "_0", 0, 0.1F, "Install LiveData with the documented command.",
            "Install LiveData with the documented command.", 2.0F, "tenant-1", "user-1", "tenant", List.of());
        SearchResult result = new SearchResult(id, id, null, null, null, id + ".md", "md", null,
            List.of(), List.of(), List.of(), 10, null, List.of(), List.of(chunk), id, 2, true,
            "tenant-1", "user-1", "tenant", List.of(), "active", null, null, null);
        return new DocumentSearchCandidate(result, 10, 0, false, true);
    }

    private DocumentSearchPlan plan() {
        return new DocumentSearchPlan("livedata install", 8, null, List.of(), List.of(), List.of(),
            "", "how_to", List.of(), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of()),
            DocumentVisibilityContext.unrestricted(), null);
    }
}
