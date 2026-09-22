package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GlobalChunkIndexServiceTest {
    @Test
    void retrievesPassagesWithinPostgresSelectedDocumentsWithoutLoadingRocksDb() {
        SearchProperties properties = new SearchProperties();
        properties.setDocumentFirstEnabled(true);
        SearchService rocksDbSearch = mock(SearchService.class);
        DocumentSearchIndex index = mock(DocumentSearchIndex.class);
        when(index.isAvailable()).thenReturn(true);
        when(index.search(eq("livedata install"), eq(50), any(SearchPermissionContext.class),
            eq(List.of("doc-1")))).thenReturn(List.of(new LuceneSearchHit(
            "doc-1", "LiveData.md", "Install", "content", "doc-1_0", 0,
            "Install LiveData with this command.", 0.1F, 3.0F,
            "tenant-1", "user-1", "tenant", List.of(), "section-1", 2)));
        GlobalChunkIndexService service = new GlobalChunkIndexService(rocksDbSearch, properties);
        ReflectionTestUtils.setField(service, "documentSearchIndex", index);
        DocumentSearchPlan plan = new DocumentSearchPlan("livedata install", 8, null,
            List.of(), List.of(), List.of("doc-1"), "doc-1", "how_to",
            new SearchTokenizer().searchTokens("livedata install"), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of()),
            DocumentVisibilityContext.unrestricted(), null);

        SearchPage page = service.recall(plan);

        assertThat(page.results()).hasSize(1);
        assertThat(page.results().get(0).matchedChunks()).hasSize(1);
        assertThat(page.results().get(0).matchedChunks().get(0).content())
            .isEqualTo("Install LiveData with this command.");
        assertThat(page.results().get(0).version()).isEqualTo(2);
        verify(index).search(eq("livedata install"), eq(50), any(SearchPermissionContext.class),
            eq(List.of("doc-1")));
        verifyNoInteractions(rocksDbSearch);
    }
}
