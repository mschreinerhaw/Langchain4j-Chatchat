package com.chatchat.knowledgebase.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchEngineCompatibilityTest {

    @Test
    void defaultEngineUsesLocalLucene() {
        SearchProperties properties = new SearchProperties();
        LuceneDocumentIndexService lucene = mock(LuceneDocumentIndexService.class);
        OpenSearchDocumentIndexService openSearch = mock(OpenSearchDocumentIndexService.class);
        LuceneSearchHit hit = hit("lucene-doc");
        when(lucene.search(eq("账户额度"), eq(10), any(SearchPermissionContext.class))).thenReturn(List.of(hit));

        CompositeDocumentSearchIndexService index = new CompositeDocumentSearchIndexService(properties, lucene, openSearch);

        assertThat(index.search("账户额度", 10)).containsExactly(hit);
        verify(lucene).search(eq("账户额度"), eq(10), any(SearchPermissionContext.class));
        verify(openSearch, never()).search(eq("账户额度"), eq(10), any(SearchPermissionContext.class));
    }

    @Test
    void openSearchEngineFallsBackToLuceneWhenOpenSearchUnavailable() {
        SearchProperties properties = new SearchProperties();
        properties.setEngine("opensearch");
        LuceneDocumentIndexService lucene = mock(LuceneDocumentIndexService.class);
        OpenSearchDocumentIndexService openSearch = mock(OpenSearchDocumentIndexService.class);
        LuceneSearchHit hit = hit("fallback-doc");
        when(openSearch.isAvailable()).thenReturn(false);
        when(lucene.search(eq("账户额度"), eq(10), any(SearchPermissionContext.class))).thenReturn(List.of(hit));

        CompositeDocumentSearchIndexService index = new CompositeDocumentSearchIndexService(properties, lucene, openSearch);

        assertThat(index.search("账户额度", 10)).containsExactly(hit);
        verify(openSearch).isAvailable();
        verify(lucene).search(eq("账户额度"), eq(10), any(SearchPermissionContext.class));
    }

    @Test
    void openSearchEngineUsesOpenSearchOnlyWhenAvailable() {
        SearchProperties properties = new SearchProperties();
        properties.setEngine("opensearch");
        LuceneDocumentIndexService lucene = mock(LuceneDocumentIndexService.class);
        OpenSearchDocumentIndexService openSearch = mock(OpenSearchDocumentIndexService.class);
        LuceneSearchHit hit = hit("opensearch-doc");
        when(openSearch.isAvailable()).thenReturn(true);
        when(openSearch.search(eq("账户额度"), eq(10), any(SearchPermissionContext.class))).thenReturn(List.of(hit));

        CompositeDocumentSearchIndexService index = new CompositeDocumentSearchIndexService(properties, lucene, openSearch);

        assertThat(index.search("账户额度", 10)).containsExactly(hit);
        verify(openSearch).search(eq("账户额度"), eq(10), any(SearchPermissionContext.class));
        verify(lucene, never()).search(eq("账户额度"), eq(10), any(SearchPermissionContext.class));
    }

    @Test
    void openSearchQueryFailureFallsBackToLocalLucene() {
        SearchProperties properties = new SearchProperties();
        properties.setEngine("opensearch");
        LuceneDocumentIndexService lucene = mock(LuceneDocumentIndexService.class);
        OpenSearchDocumentIndexService openSearch = mock(OpenSearchDocumentIndexService.class);
        LuceneSearchHit hit = hit("clause-overflow-fallback");
        when(openSearch.isAvailable()).thenReturn(true);
        when(openSearch.search(eq("复杂查询"), eq(10), any(SearchPermissionContext.class)))
            .thenThrow(new IllegalStateException("too_many_nested_clauses"));
        when(lucene.isAvailable()).thenReturn(true);
        when(lucene.search(eq("复杂查询"), eq(10), any(SearchPermissionContext.class))).thenReturn(List.of(hit));

        CompositeDocumentSearchIndexService index = new CompositeDocumentSearchIndexService(properties, lucene, openSearch);

        assertThat(index.search("复杂查询", 10)).containsExactly(hit);
        verify(lucene).search(eq("复杂查询"), eq(10), any(SearchPermissionContext.class));
    }

    @Test
    void embeddingClientStaysDisabledForLocalLuceneEngine() {
        SearchProperties properties = new SearchProperties();
        properties.setEngine("lucene");
        SearchProperties.OpenSearch.Embedding embedding = properties.getOpenSearch().getEmbedding();
        embedding.setEnabled(true);
        embedding.setEndpoint("https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings");
        embedding.setApiKey("test-key");

        OpenSearchEmbeddingClient client = new OpenSearchEmbeddingClient(properties, new ObjectMapper());

        assertThat(client.enabled()).isFalse();
    }

    private LuceneSearchHit hit(String docId) {
        return new LuceneSearchHit(
            docId,
            docId + ".md",
            "section",
            "content",
            docId + "_0",
            0,
            "账户额度测试内容",
            0.0F,
            1.0F,
            "default",
            "admin",
            "tenant",
            List.of()
        );
    }
}
