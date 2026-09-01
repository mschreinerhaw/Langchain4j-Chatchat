package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.chatchat.runtime.news.search.TencentWebSearchClient;
import com.chatchat.runtime.news.search.WebSearchCache;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class WebSearchToolExecutorTest {

    @Test
    @SuppressWarnings("unchecked")
    void splitsLocalNewsKeywordsAndAggregatesUniqueCandidatesBeforeRanking() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(true);
        properties.getWebSearch().setMinimumLocalResults(3);
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        when(store.search(any())).thenAnswer(invocation -> {
            NewsSearchQuery search = invocation.getArgument(0);
            return switch (search.query()) {
                case "芯片" -> List.of(topicDocument("chip", "芯片"));
                case "人工智能" -> List.of(topicDocument("ai", "人工智能"));
                case "算力" -> List.of(topicDocument("compute", "算力"));
                default -> List.of();
            };
        });
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);

        var output = new WebSearchToolExecutor(store, properties, external).execute(
            ToolInput.builder().parameters(Map.of(
                "query", "芯片、人工智能、算力", "num_results", 10)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("localSearchStrategy", "original_plus_keyword_fanout_v1")
            .containsEntry("localSearchTerms", List.of("芯片", "人工智能", "算力"))
            .containsEntry("localSearchQueryCount", 4)
            .containsEntry("newsIndexCandidateCount", 3)
            .containsEntry("qualifiedLocalNewsCount", 3)
            .containsEntry("localEvidenceSufficient", true);
        assertThat((List<Map<String, Object>>) data.get("results")).hasSize(3);
        verify(store, times(4)).search(any());
        verify(external, never()).search(any(), any(Integer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sufficientLocalEvidenceSkipsCacheAndPaidExternalApi() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(true);
        properties.getWebSearch().setMinimumLocalResults(3);
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        when(store.search(any())).thenReturn(List.of(
            document("local-1"), document("local-2"), document("local-3")));
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);
        WebSearchCache cache = mock(WebSearchCache.class);
        when(cache.enabled()).thenReturn(true);

        var output = new WebSearchToolExecutor(store, properties, external, cache).execute(
            ToolInput.builder().parameters(Map.of("query", "market hotspots", "num_results", 10)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("mode", "news_index")
            .containsEntry("localEvidenceSufficient", true)
            .containsEntry("externalSearchRequired", false)
            .containsEntry("externalSearchRole", "supplementary_fallback")
            .containsEntry("externalWebCount", 0);
        verify(cache, never()).findHighlyRelated(any());
        verify(external, never()).search(any(), any(Integer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sufficientUpstreamLocalEvidenceAlsoSkipsPaidExternalApi() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(true);
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        when(store.search(any())).thenReturn(List.of());
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);

        var output = new WebSearchToolExecutor(store, properties, external).execute(
            ToolInput.builder()
                .parameters(Map.of("query", "market quote"))
                .context(Map.of("upstreamLocalEvidenceCount", 3))
                .build());

        assertThat(output.isSuccess()).isTrue();
        assertThat((Map<String, Object>) output.getData())
            .containsEntry("localEvidenceSufficient", true)
            .containsEntry("upstreamLocalEvidenceCount", 3)
            .containsEntry("externalSearchRequired", false);
        verify(external, never()).search(any(), any(Integer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lowQualityLocalCandidatesDoNotSuppressExternalSupplement() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(true);
        properties.getWebSearch().setMinimumLocalResults(3);
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        when(store.search(any())).thenReturn(List.of(
            irrelevantDocument("noise-1"), irrelevantDocument("noise-2"), irrelevantDocument("noise-3")));
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);
        when(external.search("A股主要指数 行情成交量", 10)).thenReturn(response("quality-fallback"));

        var output = new WebSearchToolExecutor(store, properties, external).execute(
            ToolInput.builder().parameters(Map.of(
                "query", "请分析A股主要指数行情和成交量是否出现异常",
                "queryTerms", List.of("A股主要指数", "行情成交量"))).build());

        assertThat(output.isSuccess()).isTrue();
        assertThat((Map<String, Object>) output.getData())
            .containsEntry("newsIndexCandidateCount", 3)
            .containsEntry("qualifiedLocalNewsCount", 0)
            .containsEntry("localEvidenceSufficient", false)
            .containsEntry("externalSearchRequired", true)
            .containsEntry("externalWebCount", 1);
        verify(external).search("A股主要指数 行情成交量", 10);
    }

    @Test
    void cancellationDuringLocalRecallStopsExternalAndCacheWork() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(true);
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        when(store.search(any())).thenThrow(new CancellationException("request cancelled"));
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);
        WebSearchCache cache = mock(WebSearchCache.class);
        when(cache.enabled()).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new WebSearchToolExecutor(store, properties, external, cache).execute(
                    ToolInput.builder().parameters(Map.of("query", "latest announcements")).build()))
            .isInstanceOf(CancellationException.class);

        verify(cache, never()).findHighlyRelated(any());
        verify(external, never()).search(any(), any(Integer.class));
        verify(cache, never()).put(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesExternalRecallWhenLocalKnowledgeStoreIsDisabled() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(false);
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);
        when(external.search("杭州 西湖 当前热点", 5)).thenReturn(new TencentWebSearchClient.SearchResponse(
            List.of(new TencentWebSearchClient.SearchPage(
                "西湖景区动态", "https://example.com/hangzhou", "2026/07/31 09:00:00",
                "杭州西湖景区最新信息", "示例站点", 0.91)), "request-1", "standard"));

        var output = new WebSearchToolExecutor(mock(NewsDocumentStore.class), properties, external)
            .execute(ToolInput.builder().parameters(
                Map.of("query", "请帮我看看杭州西湖附近最近有什么热点",
                    "queryTerms", List.of("杭州", "西湖", "当前热点"), "num_results", 5)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("mode", "external_web_search")
            .containsEntry("externalProvider", "tencent-wsa")
            .containsEntry("externalSearchQuery", "杭州 西湖 当前热点")
            .containsEntry("externalSearchQuerySource", "analyzed_keywords")
            .containsEntry("count", 1);
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results.get(0)).containsEntry("resultType", "web")
            .containsEntry("retrievalSource", "tencent_wsa")
            .containsEntry("url", "https://example.com/hangzhou");
    }

    @Test
    @SuppressWarnings("unchecked")
    void degradesToLocalRecallWhenExternalSearchFails() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(true);
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        when(store.search(any())).thenReturn(List.of());
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);
        when(external.search("领域知识检索", 10)).thenThrow(new IllegalStateException("rate limited"));

        var output = new WebSearchToolExecutor(store, properties, external).execute(
            ToolInput.builder().parameters(Map.of("query", "领域知识", "intent", "领域知识检索")).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat((List<String>) data.get("warnings")).singleElement()
            .asString().contains("tencent_wsa", "rate limited");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reusesHighlyRelatedCachedResponseWithoutCallingPaidApi() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(true);
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        when(store.search(any())).thenReturn(List.of());
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);
        TencentWebSearchClient.SearchResponse response = response("cached-request");
        WebSearchCache cache = mock(WebSearchCache.class);
        when(cache.enabled()).thenReturn(true);
        when(cache.findHighlyRelated("West Lake current hotspots"))
            .thenReturn(java.util.Optional.of(new WebSearchCache.CachedSearch(
                "Hangzhou West Lake current hotspots", 0.92D, response)));

        var output = new WebSearchToolExecutor(store, properties, external, cache).execute(
            ToolInput.builder().parameters(Map.of(
                "query", "Could you find the latest information about Hangzhou West Lake?",
                "queryTerms", List.of("West Lake", "current hotspots"))).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("webSearchCacheHit", true)
            .containsEntry("externalProvider", "tencent-wsa-cache")
            .containsEntry("cachedQuery", "Hangzhou West Lake current hotspots");
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results.get(0)).containsEntry("retrievalSource", "tencent_wsa_cache");
        verify(external, never()).search(any(), any(Integer.class));
    }

    @Test
    void forceExternalBypassesCacheReadAndRefreshesCache() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(false);
        properties.getWebSearch().getCache().setForceExternal(true);
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);
        when(external.search("fresh analyzed terms", 10)).thenReturn(response("fresh-request"));
        WebSearchCache cache = mock(WebSearchCache.class);
        when(cache.enabled()).thenReturn(true);

        var output = new WebSearchToolExecutor(mock(NewsDocumentStore.class), properties, external, cache)
            .execute(ToolInput.builder().parameters(Map.of(
                "query", "fresh query", "queryTerms", List.of("fresh analyzed terms"))).build());

        assertThat(output.isSuccess()).isTrue();
        verify(cache, never()).findHighlyRelated(any());
        verify(external).search("fresh analyzed terms", 10);
        verify(cache).put("fresh analyzed terms", response("fresh-request"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachedResultRemainsUsableWhenExternalProviderIsTemporarilyDisabled() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(false);
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(false);
        WebSearchCache cache = mock(WebSearchCache.class);
        when(cache.enabled()).thenReturn(true);
        when(cache.findHighlyRelated("cached analyzed terms")).thenReturn(java.util.Optional.of(
            new WebSearchCache.CachedSearch("cached only", 1D, response("cached-request"))));

        var output = new WebSearchToolExecutor(mock(NewsDocumentStore.class), properties, external, cache)
            .execute(ToolInput.builder().parameters(Map.of(
                "query", "cached only", "keywords", List.of("cached analyzed terms"))).build());

        assertThat(output.isSuccess()).isTrue();
        assertThat((Map<String, Object>) output.getData()).containsEntry("webSearchCacheHit", true);
        verify(external, never()).search(any(), any(Integer.class));
    }

    private TencentWebSearchClient.SearchResponse response(String requestId) {
        return new TencentWebSearchClient.SearchResponse(List.of(
            new TencentWebSearchClient.SearchPage("Title", "https://example.com/page",
                "2026-07-31", "Current information", "Example", 0.95D)
        ), requestId, "standard");
    }

    private NewsDocument document(String id) {
        return new NewsDocument(id, 1L, "local source", NewsSourceType.RSS,
            "Local title " + id, "Local content " + id, "Local summary " + id, "author",
            "https://example.com/" + id, Instant.parse("2026-08-09T00:00:00Z"),
            Instant.parse("2026-08-09T00:01:00Z"), "en", List.of("market"), List.of(),
            "hash-" + id, NewsAnalysisStatus.COMPLETED, Map.of());
    }

    private NewsDocument topicDocument(String id, String topic) {
        return new NewsDocument(id, 1L, "local source", NewsSourceType.RSS,
            topic + "产业进展", topic + "行业最新进展与市场动态", topic + "行业摘要", "author",
            "https://example.com/" + id, Instant.parse("2026-08-09T00:00:00Z"),
            Instant.parse("2026-08-09T00:01:00Z"), "zh-CN", List.of(topic), List.of(),
            "hash-" + id, NewsAnalysisStatus.COMPLETED, Map.of());
    }

    private NewsDocument irrelevantDocument(String id) {
        return new NewsDocument(id, 1L, "local source", NewsSourceType.RSS,
            "债券担保业务 " + id, "债券托管及质押业务规模", "债券月度统计", "author",
            "https://example.com/" + id, Instant.parse("2026-08-09T00:00:00Z"),
            Instant.parse("2026-08-09T00:01:00Z"), "zh-CN", List.of("债券"), List.of("担保品"),
            "hash-" + id, NewsAnalysisStatus.COMPLETED, Map.of());
    }
}
