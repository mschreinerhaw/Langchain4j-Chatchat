package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.search.TencentWebSearchClient;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSearchToolExecutorTest {

    @Test
    @SuppressWarnings("unchecked")
    void usesExternalRecallWhenLocalKnowledgeStoreIsDisabled() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(false);
        TencentWebSearchClient external = mock(TencentWebSearchClient.class);
        when(external.enabled()).thenReturn(true);
        when(external.search("杭州西湖附近热点", 5)).thenReturn(new TencentWebSearchClient.SearchResponse(
            List.of(new TencentWebSearchClient.SearchPage(
                "西湖景区动态", "https://example.com/hangzhou", "2026/07/31 09:00:00",
                "杭州西湖景区最新信息", "示例站点", 0.91)), "request-1", "standard"));

        var output = new WebSearchToolExecutor(mock(NewsDocumentStore.class), properties, external)
            .execute(ToolInput.builder().parameters(
                Map.of("query", "杭州西湖附近热点", "num_results", 5)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("mode", "external_web_search")
            .containsEntry("externalProvider", "tencent-wsa")
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
        when(external.search("领域知识", 10)).thenThrow(new IllegalStateException("rate limited"));

        var output = new WebSearchToolExecutor(store, properties, external).execute(
            ToolInput.builder().parameters(Map.of("query", "领域知识")).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat((List<String>) data.get("warnings")).singleElement()
            .asString().contains("tencent_wsa", "rate limited");
    }
}
