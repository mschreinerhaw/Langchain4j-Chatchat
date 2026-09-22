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

class DocumentSearchOrchestratorIrTest {
    @Test
    void chapterRecallPromotesNamedDocumentUsingSamePermissionScope() {
        GlobalDocumentIndexService documents = mock(GlobalDocumentIndexService.class);
        GlobalChunkIndexService chunks = mock(GlobalChunkIndexService.class);
        KnowledgeIrDocumentRecall ir = mock(KnowledgeIrDocumentRecall.class);
        SearchPage genericPage = page(result("linux-doc", "Linux maintenance"));
        SearchPage namedPage = page(result("livedata-doc", "livedata install"));
        when(documents.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenAnswer(invocation -> {
                DocumentSearchPlan request = invocation.getArgument(0);
                if (request.query().equals("livedata")) {
                    assertThat(request.permissionContext().tenantId()).isEqualTo("tenant-1");
                    assertThat(request.joinedVisibilityScopeIds()).isEqualTo("livedata-doc");
                    return namedPage;
                }
                return genericPage;
            });
        when(ir.recall(any(DocumentSearchPlan.class), eq(8)))
            .thenReturn(new KnowledgeIrDocumentRecall.Recall(List.of("livedata-doc"), "livedata"));
        DocumentSearchOrchestrator orchestrator = new DocumentSearchOrchestrator(
            documents, chunks, new SearchProperties(), new IndexVersionManager(), ir);
        DocumentSearchPlan plan = new DocumentSearchPlan(
            "livedata 安装说明 installation guide", 8, null, List.of(), List.of(), List.of(),
            "", "how_to", List.of(), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of()),
            DocumentVisibilityContext.unrestricted(), null);

        assertThat(orchestrator.recall(plan, 8).candidates())
            .extracting(candidate -> candidate.result().docId())
            .containsExactly("livedata-doc", "linux-doc");
    }

    private SearchPage page(SearchResult result) {
        return new SearchPage("query", List.of(), List.of(result), 1, 8, 1, 8, 1,
            false, 1L, 1, null);
    }

    private SearchResult result(String id, String title) {
        return new SearchResult(id, title, title, "upload", "2026-09-22", title + ".md",
            "md", null, List.of(), List.of(), List.of(), 20, null, List.of(), List.of(),
            id, 1, true, "tenant-1", "user-1", "tenant", List.of(), "active",
            System.currentTimeMillis(), null, null);
    }
}
