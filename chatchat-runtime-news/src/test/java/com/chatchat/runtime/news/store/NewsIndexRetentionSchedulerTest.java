package com.chatchat.runtime.news.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsIndexRetentionSchedulerTest {

    @Test
    void ensuresCurrentDailyIndexBeforeRetentionCleanup() throws Exception {
        OpenSearchNewsDocumentStore store = mock(OpenSearchNewsDocumentStore.class);
        when(store.ensureDailyIndex()).thenReturn("runtime-news-2026.07.17");
        when(store.deleteExpiredIndices()).thenReturn(List.of());

        new NewsIndexRetentionScheduler(store).cleanup();

        var ordered = inOrder(store);
        ordered.verify(store).ensureDailyIndex();
        ordered.verify(store).deleteExpiredIndices();
    }
}
