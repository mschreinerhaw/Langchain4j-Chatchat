package com.chatchat.runtime.news.analysis;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import com.chatchat.runtime.news.store.NewsIndexStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsAnalysisSchedulerTest {

    @Test
    void analyzesClaimedDocumentAndCompletesTask() throws Exception {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        NewsIndexStateService state = mock(NewsIndexStateService.class);
        NewsDocumentStore store = mock(NewsDocumentStore.class);
        NewsAnalysisTaskEntity task = new NewsAnalysisTaskEntity();
        task.setId(7L);
        task.setDocumentId("doc-7");
        when(state.claimAnalysisTasks(20)).thenReturn(List.of(task));
        when(store.findById("doc-7")).thenReturn(Optional.of(document()));
        NewsAnalysisScheduler scheduler = new NewsAnalysisScheduler(properties, state, store,
            new NewsContentAnalyzer(properties));

        scheduler.analyzePending();

        ArgumentCaptor<List<NewsDocument>> documents = ArgumentCaptor.forClass(List.class);
        verify(store).bulkIndex(documents.capture());
        assertThat(documents.getValue()).singleElement().extracting(NewsDocument::analysisStatus)
            .isEqualTo(NewsAnalysisStatus.COMPLETED);
        verify(state).analysisCompleted(7L, "doc-7");
    }

    private NewsDocument document() {
        return new NewsDocument("doc-7", 1L, "测试资讯源", NewsSourceType.WEB_LIST, "央行发布消息",
            "央行发布新的货币政策安排。", null, null, "https://example.com/7", Instant.now(), Instant.now(),
            "zh-CN", List.of(), List.of(), "hash", NewsAnalysisStatus.PENDING, Map.of());
    }
}
