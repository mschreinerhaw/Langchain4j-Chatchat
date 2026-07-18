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

    @Test
    void upgradesExistingSseHomepagePresetWhilePreservingEnabledState() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":9,"sourceCode":"sse_home","sourceName":"上海证券交易所首页",
              "sourceType":"EXCHANGE_HOME","entryUrl":"https://www.sse.com.cn/",
              "enabled":true,"configuration":{"provider":"SSE","presetVersion":1}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/9"), request.capture());
        assertThat(request.getValue().enabled()).isTrue();
        assertThat(request.getValue().configuration()).containsKeys(
            "sectionSelectors", "announcementFeeds", "marketDataFeeds");
        assertThat(request.getValue().configuration()).containsEntry("presetVersion", 2);
    }

    @Test
    void upgradesExistingEastmoneySelectorsWhilePreservingEnabledState() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":10,"sourceCode":"eastmoney_finance","sourceName":"东方财富财经",
              "sourceType":"WEB_LIST","entryUrl":"https://finance.eastmoney.com/",
              "enabled":true,"configuration":{"presetVersion":1}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var sourceRequest = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/10"), sourceRequest.capture());
        assertThat(sourceRequest.getValue().enabled()).isTrue();
        assertThat(sourceRequest.getValue().configuration()).containsEntry("presetVersion", 2);

        var ruleRequest = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.RuleUpsert.class);
        verify(runtime).put(eq("/sources/10/rule"), ruleRequest.capture());
        assertThat(ruleRequest.getValue().titleSelector()).isEqualTo(".title");
        assertThat(ruleRequest.getValue().urlPattern()).contains("/a/\\d+");
    }
}
