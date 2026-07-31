package com.chatchat.runtime.news.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSearchCacheRetentionSchedulerTest {
    @Test
    void deletesExpiredCacheIndicesWhenEnabled() throws Exception {
        WebSearchCache cache = mock(WebSearchCache.class);
        when(cache.enabled()).thenReturn(true);
        when(cache.deleteExpiredIndices()).thenReturn(List.of("runtime-web-search-cache-2026.07.20"));

        new WebSearchCacheRetentionScheduler(cache).cleanup();

        verify(cache).deleteExpiredIndices();
    }
}
