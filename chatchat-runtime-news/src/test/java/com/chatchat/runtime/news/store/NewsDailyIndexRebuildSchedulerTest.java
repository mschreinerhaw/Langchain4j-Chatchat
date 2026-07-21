package com.chatchat.runtime.news.store;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsDailyIndexRebuildSchedulerTest {

    @Test
    void preparesTheCalendarDayNewsIndex() throws Exception {
        OpenSearchNewsDocumentStore store = mock(OpenSearchNewsDocumentStore.class);
        when(store.ensureDailyIndex()).thenReturn("runtime-news-2026.07.22");

        new NewsDailyIndexRebuildScheduler(store).rebuild();

        verify(store).ensureDailyIndex();
    }
}
