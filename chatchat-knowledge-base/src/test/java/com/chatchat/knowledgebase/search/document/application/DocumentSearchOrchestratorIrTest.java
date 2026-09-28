package com.chatchat.knowledgebase.search.document;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.GlobalChunkIndexService;
import com.chatchat.knowledgebase.search.index.GlobalDocumentIndexService;
import com.chatchat.knowledgebase.search.index.IndexVersionManager;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.model.SearchResult;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class DocumentSearchOrchestratorIrTest {
    @Test
    void documentFirstStopsWhenPostgresFindsNoDocument() {
        GlobalDocumentIndexService documents = mock(GlobalDocumentIndexService.class);
        GlobalChunkIndexService chunks = mock(GlobalChunkIndexService.class);
        KnowledgeIrDocumentRecall ir = mock(KnowledgeIrDocumentRecall.class);
        when(ir.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenReturn(new KnowledgeIrDocumentRecall.Recall(List.of(), "livedata"));
        SearchProperties properties = new SearchProperties();
        properties.setDocumentFirstEnabled(true);
        DocumentSearchOrchestrator orchestrator = new DocumentSearchOrchestrator(
            documents, chunks, properties, new IndexVersionManager(), ir);

        DocumentRecallResult result = orchestrator.recall(plan(), 8);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.irDocumentIds()).isEmpty();
        verifyNoInteractions(documents, chunks);
    }

    @Test
    void documentFirstSearchesOnlySelectedIds() {
        GlobalDocumentIndexService documents = mock(GlobalDocumentIndexService.class);
        GlobalChunkIndexService chunks = mock(GlobalChunkIndexService.class);
        KnowledgeIrDocumentRecall ir = mock(KnowledgeIrDocumentRecall.class);
        when(ir.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenReturn(new KnowledgeIrDocumentRecall.Recall(List.of("livedata-doc"), "livedata"));
        when(chunks.recall(any(DocumentSearchPlan.class))).thenAnswer(invocation -> {
            DocumentSearchPlan scoped = invocation.getArgument(0);
            assertThat(scoped.visibilityScopeIds()).containsExactly("livedata-doc");
            return page(result("livedata-doc", "LiveData install"));
        });
        SearchProperties properties = new SearchProperties();
        properties.setDocumentFirstEnabled(true);
        DocumentSearchOrchestrator orchestrator = new DocumentSearchOrchestrator(
            documents, chunks, properties, new IndexVersionManager(), ir);

        assertThat(orchestrator.recall(plan(), 8).candidates())
            .extracting(candidate -> candidate.result().docId()).containsExactly("livedata-doc");
        verifyNoInteractions(documents);
    }

    @Test
    void documentAndChunkRanksFuseWithoutComparingRawIndexScores() {
        GlobalDocumentIndexService documents = mock(GlobalDocumentIndexService.class);
        GlobalChunkIndexService chunks = mock(GlobalChunkIndexService.class);
        KnowledgeIrDocumentRecall ir = mock(KnowledgeIrDocumentRecall.class);
        SearchResult generic = result("generic-doc", "General installation guide", 900);
        SearchResult corroborated = result("corroborated-doc", "Product deployment", 10);
        when(documents.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenReturn(page(List.of(generic, corroborated)));
        when(chunks.recall(any(DocumentSearchPlan.class)))
            .thenReturn(page(List.of(corroborated)));
        when(ir.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenReturn(new KnowledgeIrDocumentRecall.Recall(List.of(), ""));
        DocumentSearchOrchestrator orchestrator = new DocumentSearchOrchestrator(
            documents, chunks, new SearchProperties(), new IndexVersionManager(), ir);

        assertThat(orchestrator.recall(plan(), 8).candidates())
            .extracting(candidate -> candidate.result().docId())
            .containsExactly("corroborated-doc", "generic-doc");
    }

    @Test
    void chapterRecallPromotesNamedDocumentUsingSamePermissionScope() {
        GlobalDocumentIndexService documents = mock(GlobalDocumentIndexService.class);
        GlobalChunkIndexService chunks = mock(GlobalChunkIndexService.class);
        KnowledgeIrDocumentRecall ir = mock(KnowledgeIrDocumentRecall.class);
        SearchPage namedPage = page(result("livedata-doc", "livedata install"));
        when(chunks.recall(any(DocumentSearchPlan.class)))
            .thenAnswer(invocation -> {
                DocumentSearchPlan request = invocation.getArgument(0);
                assertThat(request.query()).isEqualTo(plan().query());
                assertThat(request.permissionContext().tenantId()).isEqualTo("tenant-1");
                assertThat(request.joinedVisibilityScopeIds()).isEqualTo("livedata-doc");
                return namedPage;
            });
        when(ir.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenReturn(new KnowledgeIrDocumentRecall.Recall(List.of("livedata-doc"), "livedata"));
        DocumentSearchOrchestrator orchestrator = new DocumentSearchOrchestrator(
            documents, chunks, new SearchProperties(), new IndexVersionManager(), ir);
        assertThat(orchestrator.recall(plan(), 8).candidates())
            .extracting(candidate -> candidate.result().docId())
            .containsExactly("livedata-doc");
        org.mockito.Mockito.verifyNoInteractions(documents);
    }

    @Test
    void retainsDatabaseDocumentWhenSearchIndexHasNoHit() {
        GlobalDocumentIndexService documents = mock(GlobalDocumentIndexService.class);
        GlobalChunkIndexService chunks = mock(GlobalChunkIndexService.class);
        KnowledgeIrDocumentRecall ir = mock(KnowledgeIrDocumentRecall.class);
        when(documents.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenReturn(page(List.of()));
        when(ir.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenReturn(new KnowledgeIrDocumentRecall.Recall(List.of("livedata-doc"), "livedata"));
        DocumentSearchOrchestrator orchestrator = new DocumentSearchOrchestrator(
            documents, chunks, new SearchProperties(), new IndexVersionManager(), ir);

        DocumentRecallResult result = orchestrator.recall(plan(), 8);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.irDocumentIds()).containsExactly("livedata-doc");
    }

    @Test
    void stopsBeforeSearchWhenDatabaseScopeCannotBeResolved() {
        GlobalDocumentIndexService documents = mock(GlobalDocumentIndexService.class);
        GlobalChunkIndexService chunks = mock(GlobalChunkIndexService.class);
        KnowledgeIrDocumentRecall ir = mock(KnowledgeIrDocumentRecall.class);
        when(ir.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenThrow(new IllegalStateException("database unavailable"));
        DocumentSearchOrchestrator orchestrator = new DocumentSearchOrchestrator(
            documents, chunks, new SearchProperties(), new IndexVersionManager(), ir);

        assertThat(orchestrator.recall(plan(), 8).candidates()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(documents, chunks);
    }

    private SearchPage page(SearchResult result) {
        return page(List.of(result));
    }

    private SearchPage page(List<SearchResult> results) {
        return new SearchPage("query", List.of(), results, results.size(), 8, 1, 8, 1,
            false, 1L, 1, null);
    }

    private SearchResult result(String id, String title) {
        return result(id, title, 20);
    }

    private SearchResult result(String id, String title, int score) {
        return new SearchResult(id, title, title, "upload", "2026-09-22", title + ".md",
            "md", null, List.of(), List.of(), List.of(), score, null, List.of(), List.of(),
            id, 1, true, "tenant-1", "user-1", "tenant", List.of(), "active",
            System.currentTimeMillis(), null, null);
    }

    private DocumentSearchPlan plan() {
        return new DocumentSearchPlan(
            "livedata 安装说明 installation guide", 8, null, List.of(), List.of(), List.of(),
            "", "how_to", List.of(), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of()),
            DocumentVisibilityContext.unrestricted(), null);
    }
}
