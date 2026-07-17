package com.chatchat.mcpserver.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NewsSourcePresetSeederTest {
    @Test
    void seedsMissingPresetsThroughRuntimeClient() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("[]"));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        boolean synchronizedSuccessfully = new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets();

        org.assertj.core.api.Assertions.assertThat(synchronizedSuccessfully).isTrue();
        verify(runtime, times(8)).post(eq("/sources"), any());
        verify(runtime, times(3)).put(contains("/rule"), any());
    }

    @Test
    void retriesAfterRuntimeBecomesAvailable() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources"))
            .thenThrow(new IllegalStateException("runtime unavailable"))
            .thenReturn(new ObjectMapper().readTree("[]"));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));
        NewsSourcePresetSeeder seeder = new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog());

        org.assertj.core.api.Assertions.assertThat(seeder.seedMissingPresets()).isFalse();
        seeder.retryInitialSynchronization();

        verify(runtime, times(2)).get("/sources");
        verify(runtime, times(8)).post(eq("/sources"), any());
    }

    @Test
    void migratesLegacyCninfoWebListToStructuredAnnouncementCollector() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":8,"sourceCode":"cninfo_announcements","sourceName":"巨潮资讯公告",
              "sourceType":"WEB_LIST","entryUrl":"https://www.cninfo.com.cn/new/commonUrl/pageOfSearch?url=disclosure/list/search",
              "enabled":true,"configuration":{}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));
        NewsSourcePresetSeeder seeder = new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog());

        assertThat(seeder.seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/8"), request.capture());
        assertThat(request.getValue().sourceType()).isEqualTo("CNINFO_ANNOUNCEMENTS");
        assertThat(request.getValue().enabled()).isTrue();
        assertThat(request.getValue().configuration()).containsKeys("apiUrl", "staticBaseUrl");
    }
}
