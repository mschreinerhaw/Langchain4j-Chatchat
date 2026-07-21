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
        verify(runtime, times(34)).post(eq("/sources"), any());
        verify(runtime, times(6)).put(contains("/rule"), any());
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
        verify(runtime, times(34)).post(eq("/sources"), any());
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
    void migratesLegacySseWebListToStructuredAnnouncementCollector() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":11,"sourceCode":"sse_announcements","sourceName":"上海证券交易所公告",
              "sourceType":"WEB_LIST","entryUrl":"https://www.sse.com.cn/disclosure/listedinfo/announcement/",
              "enabled":true,"configuration":{}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/11"), request.capture());
        assertThat(request.getValue().sourceType()).isEqualTo("SSE_ANNOUNCEMENTS");
        assertThat(request.getValue().enabled()).isTrue();
        assertThat(request.getValue().entryUrl()).contains("/disclosure/listedinfo/announcement/");
        assertThat(request.getValue().configuration()).containsKeys("feeds", "itemLimit", "presetVersion");
    }

    @Test
    void upgradesClsToCursorPaginationWhilePreservingEnabledState() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":15,"sourceCode":"cls_telegraph","sourceName":"财联社电报",
              "sourceType":"CLS_TELEGRAPH","entryUrl":"https://www.cls.cn/telegraph",
              "enabled":true,"configuration":{"apiUrl":"https://www.cls.cn/api/cache","itemLimit":30}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/15"), request.capture());
        assertThat(request.getValue().enabled()).isTrue();
        assertThat(request.getValue().sourceType()).isEqualTo("STRUCTURED_FLASH");
        assertThat(request.getValue().configuration())
            .containsEntry("presetVersion", 3)
            .containsEntry("legalRisk", true)
            .containsEntry("maxPagesPerRun", 200)
            .containsKeys("request", "response", "mapping", "compliance", "initialBackfillHours");
    }

    @Test
    void migratesExistingClsPresetToStructuredTemplate() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":16,"sourceCode":"cls_telegraph","sourceName":"财联社电报",
              "sourceType":"CLS_TELEGRAPH","entryUrl":"https://www.cls.cn/telegraph",
              "enabled":false,"configuration":{"presetVersion":2,"itemLimit":37,"customSetting":"keep"}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/16"), request.capture());
        assertThat(request.getValue().sourceType()).isEqualTo("STRUCTURED_FLASH");
        assertThat(request.getValue().configuration())
            .containsEntry("legalRisk", true)
            .containsEntry("presetVersion", 3)
            .containsKeys("request", "response", "mapping", "compliance");
    }

    @Test
    void migratesExistingEastmoney724PresetToStructuredTemplate() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":19,"sourceCode":"eastmoney_724","sourceName":"东方财富全球财经资讯 7×24 小时直播",
              "sourceType":"EASTMONEY_724","entryUrl":"https://kuaixun.eastmoney.com/",
              "enabled":true,"configuration":{"presetVersion":1,"itemLimit":50}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/19"), request.capture());
        assertThat(request.getValue().enabled()).isTrue();
        assertThat(request.getValue().sourceType()).isEqualTo("STRUCTURED_FLASH");
        assertThat(request.getValue().configuration())
            .containsEntry("presetVersion", 2)
            .containsEntry("legalRisk", true)
            .containsKeys("request", "response", "mapping", "compliance");
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
    void upgradesExistingSzseHomepageToDynamicCollectorWhilePreservingEnabledState() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":13,"sourceCode":"szse_home","sourceName":"深圳证券交易所首页",
              "sourceType":"SZSE_HOME","entryUrl":"https://www.szse.cn/index/index.html",
              "collectionDescription":"采集深交所要闻、深交所公告和上市公司公告及其二级正文或 PDF。",
              "enabled":true,"configuration":{"provider":"SZSE","presetVersion":2}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/13"), request.capture());
        assertThat(request.getValue().sourceType()).isEqualTo("SZSE_HOME");
        assertThat(request.getValue().enabled()).isTrue();
        assertThat(request.getValue().collectionDescription()).contains("深证成指", "创业板指");
        assertThat(request.getValue().configuration()).containsKeys(
            "newsSelector", "noticeIndexUrl", "announcementApiUrl", "marketUrlTemplate", "marketCodes");
        assertThat(request.getValue().configuration()).containsEntry("presetVersion", 3);
    }

    @Test
    void upgradesExistingCninfoHomepageToDynamicCollectorWhilePreservingEnabledState() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":14,"sourceCode":"cninfo_home","sourceName":"巨潮资讯首页",
              "sourceType":"NEWS_HOME","entryUrl":"https://www.cninfo.com.cn/new/index",
              "enabled":true,"configuration":{}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/14"), request.capture());
        assertThat(request.getValue().sourceType()).isEqualTo("CNINFO_HOME");
        assertThat(request.getValue().enabled()).isTrue();
        assertThat(request.getValue().configuration()).containsKeys(
            "announcementApiUrl", "answersUrl", "researchUrl", "votingUrl", "publicInfoUrl");
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
        assertThat(sourceRequest.getValue().configuration())
            .containsEntry("presetVersion", 2)
            .containsEntry("legalRisk", true);

        var ruleRequest = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.RuleUpsert.class);
        verify(runtime).put(eq("/sources/10/rule"), ruleRequest.capture());
        assertThat(ruleRequest.getValue().titleSelector()).isEqualTo(".title");
        assertThat(ruleRequest.getValue().urlPattern()).contains("/a/\\d+");
    }

    @Test
    void addsLegalRiskTagToExistingEastmoneyPresetWithoutReplacingItsConfiguration() throws Exception {
        NewsRuntimeClient runtime = mock(NewsRuntimeClient.class);
        when(runtime.get("/sources")).thenReturn(new ObjectMapper().readTree("""
            [{"id":18,"sourceCode":"eastmoney_finance","sourceName":"东方财富财经",
              "sourceType":"WEB_LIST","entryUrl":"https://finance.eastmoney.com/",
              "enabled":false,"configuration":{"presetVersion":2,"customSetting":"keep"}}]
            """));
        when(runtime.post(eq("/sources"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":12}"));

        assertThat(new NewsSourcePresetSeeder(runtime, new NewsSourcePresetCatalog()).seedMissingPresets()).isTrue();

        var request = org.mockito.ArgumentCaptor.forClass(NewsSourcePresetCatalog.SourceUpsert.class);
        verify(runtime).put(eq("/sources/18"), request.capture());
        assertThat(request.getValue().collectionDescription()).isNotBlank();
        assertThat(request.getValue().configuration())
            .containsEntry("legalRisk", true)
            .containsEntry("customSetting", "keep");
    }
}
