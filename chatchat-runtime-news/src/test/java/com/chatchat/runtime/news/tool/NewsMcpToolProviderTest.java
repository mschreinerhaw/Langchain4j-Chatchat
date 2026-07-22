package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.source.NewsCollectRecordRepository;
import com.chatchat.runtime.news.source.NewsSourceRepository;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsMcpToolProviderTest {
    @Test
    void exposesOnlyNewsRuntimeExecutors() {
        NewsMcpToolProvider provider = provider(mock(NewsDocumentStore.class), properties(true));

        assertThat(List.of("web_search", "news_search", "news_latest", "news_source_status"))
            .allSatisfy(name -> assertThat(provider.findExecutor(name)).isPresent());
        assertThat(provider.findExecutor("search_financial_dataset")).isEmpty();
        assertThat(provider.findExecutor("get_financial_data")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchUsesOnlyNewsStore() throws Exception {
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        when(store.search(any())).thenReturn(List.of(new NewsDocument("news-1", 1L, "财经源", NewsSourceType.RSS,
            "业绩预告", "公司发布半年度业绩预告正文", "公司发布业绩预告", "记者",
            "https://example.com/news/1", Instant.parse("2026-07-17T01:00:00Z"), Instant.now(), "zh-CN",
            List.of("业绩"), List.of(), "hash", NewsAnalysisStatus.PENDING, Map.of())));
        NewsMcpToolProvider provider = provider(store, properties(true));

        var output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", "业绩预告", "num_results", 5)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("provider", "chatchat-runtime-news")
            .containsEntry("mode", "news_index").containsEntry("count", 1);
        assertThat((List<String>) data.get("reference_urls")).containsExactly("https://example.com/news/1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsUnavailableStoreInsteadOfEmptyResults() {
        NewsMcpToolProvider provider = provider(mock(NewsDocumentStore.class), properties(false));
        var output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", "test")).build());

        assertThat(output.isSuccess()).isFalse();
        assertThat((Map<String, Object>) output.getData()).containsEntry("code", "NEWS_STORE_UNAVAILABLE")
            .containsEntry("capability", "news");
    }

    private NewsMcpToolProvider provider(NewsDocumentStore store, NewsRuntimeProperties properties) {
        return new NewsMcpToolProvider(new WebSearchToolExecutor(store, properties),
            new NewsSearchToolExecutor(store, properties), new NewsLatestToolExecutor(store, properties),
            new NewsSourceStatusToolExecutor(mock(NewsSourceRepository.class), mock(NewsCollectRecordRepository.class)));
    }

    private NewsRuntimeProperties properties(boolean enabled) {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEnabled(enabled);
        return properties;
    }
}
