package com.chatchat.runtime.news.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsIndexRetentionSchedulerTest {

    @Test
    void removesExpiredIndicesWithoutRebuildingTheDailyIndex() throws Exception {
        OpenSearchNewsDocumentStore store = mock(OpenSearchNewsDocumentStore.class);
        when(store.deleteExpiredIndices()).thenReturn(List.of());

        new NewsIndexRetentionScheduler(store).cleanup();

        verify(store).deleteExpiredIndices();
    }
}
