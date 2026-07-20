package com.chatchat.mcpserver.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsSourcePresetCatalogTest {
    @Test
    void providesSafeDisabledNewsPresetsIncludingExchangeHomeSnapshots() {
        var presets = new NewsSourcePresetCatalog().presets();
        assertThat(presets).extracting(NewsSourcePresetCatalog.Preset::code).containsExactly(
            "sse_home", "szse_home", "szse_fund_etf_announcements", "szse_margin_business_announcements",
            "szse_auction_public_information", "szse_listing_disclosure", "eastmoney_finance", "cls_telegraph", "sse_announcements",
            "sse_listing_announcements", "sse_trading_suspension_announcements", "sse_general_announcements",
            "sse_intraday_suspension", "sse_margin_announcements", "sse_fund_announcements",
            "sse_bond_announcements", "sse_option_announcements", "sse_ipo_latest",
            "szse_announcements", "cninfo_home", "cninfo_announcements", "cninfo_latest_sections");
        assertThat(presets).filteredOn(preset -> "EXCHANGE_HOME".equals(preset.source().sourceType()))
            .hasSize(1).allSatisfy(preset -> {
                assertThat(preset.rule()).isNull();
                assertThat(preset.source().configuration()).containsKeys("provider", "headlineSelector", "marketUrlTemplate", "marketCodes");
            });
        assertThat(presets).filteredOn(preset -> "szse_home".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("SZSE_HOME");
                assertThat(preset.source().configuration()).containsKeys(
                    "presetVersion", "newsSelector", "newsUrlContains", "noticeIndexUrl", "announcementApiUrl",
                    "announcementStaticBaseUrl", "detailSelector");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "cninfo_home".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("CNINFO_HOME");
                assertThat(preset.source().configuration()).containsKeys(
                    "presetVersion", "announcementApiUrl", "answersUrl", "researchUrl", "votingUrl", "publicInfoUrl");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "sse_home".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().configuration()).containsKeys(
                    "presetVersion", "sectionSelectors", "announcementFeeds", "marketDataFeeds",
                    "detailAllowedDomains", "attachmentAllowedDomains", "ipoIntroductionUrlTemplate", "ipoOverviewUrlTemplate");
                assertThat(preset.source().configuration().get("marketCodes")).asList()
                    .containsExactly("000001", "000680", "000888", "000016", "000688", "000010", "000300");
            });
        assertThat(presets).filteredOn(preset -> "szse_announcements".equals(preset.code())).singleElement()
            .satisfies(preset -> assertThat(preset.source().entryUrl()).isEqualTo("https://www.szse.cn/disclosure/listed/notice/"));
        assertThat(presets).filteredOn(preset -> "SZSE_DISCLOSURE".equals(preset.source().sourceType()))
            .hasSize(4).allSatisfy(preset -> {
                assertThat(preset.source().configuration()).containsKeys("feeds", "itemLimit", "provider");
                assertThat(preset.description()).containsAnyOf("二级", "披露文件");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "sse_announcements".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("SSE_ANNOUNCEMENTS");
                assertThat(preset.source().entryUrl())
                    .isEqualTo("https://www.sse.com.cn/disclosure/listedinfo/announcement/");
                assertThat(preset.source().configuration()).containsKeys("feeds", "itemLimit");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "cls_telegraph".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().configuration())
                    .containsEntry("presetVersion", 2)
                    .containsEntry("itemLimit", 20)
                    .containsEntry("maxPagesPerRun", 200)
                    .containsEntry("initialBackfillHours", 24)
                    .containsEntry("minimumContentChars", 1)
                    .containsKey("rollApiUrl");
                assertThat(preset.description()).contains("断点续采");
            });
        assertThat(presets).filteredOn(preset -> "sse_bond_announcements".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("SSE_ANNOUNCEMENTS");
                assertThat(preset.source().configuration().get("feeds")).asList().hasSize(5);
                assertThat(preset.description()).contains("最新一页", "二级");
            });
        assertThat(presets).filteredOn(preset -> preset.code().startsWith("sse_")).hasSize(11)
            .allSatisfy(preset -> assertThat(preset.source().enabled()).isFalse());
        assertThat(presets).filteredOn(preset -> "eastmoney_finance".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().configuration()).containsEntry("presetVersion", 2);
                assertThat(preset.source().configuration()).containsEntry("legalRisk", true);
                assertThat(preset.rule().titleSelector()).isEqualTo(".title");
                assertThat(preset.rule().contentSelector()).isEqualTo("#ContentBody");
                assertThat(preset.rule().urlPattern()).contains("/a/\\d+");
            });
        assertThat(presets).filteredOn(preset -> "cls_telegraph".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("CLS_TELEGRAPH");
                assertThat(preset.source().configuration()).containsEntry("legalRisk", true);
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "cninfo_announcements".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("CNINFO_ANNOUNCEMENTS");
                assertThat(preset.source().configuration())
                    .containsEntry("apiUrl", "https://www.cninfo.com.cn/new/disclosure")
                    .containsEntry("column", "szse_latest")
                    .containsEntry("importantOnly", true)
                    .containsKeys("staticBaseUrl", "itemLimit");
                assertThat(preset.source().configuration()).doesNotContainKey("lookbackHours");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "cninfo_latest_sections".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("CNINFO_ANNOUNCEMENTS");
                assertThat(preset.source().configuration().get("columns")).asList().hasSize(12);
                assertThat(preset.source().configuration())
                    .doesNotContainKeys("importantOnly", "lookbackHours")
                    .containsEntry("itemLimit", 30);
                assertThat(preset.description()).contains("全部板块", "最新一页", "二级");
            });
        assertThat(presets).allSatisfy(preset -> {
            assertThat(preset.source().enabled()).isFalse();
            assertThat(preset.source().scheduleCron()).isNotBlank();
            assertThat(preset.source().configuration()).containsKeys("sleepMillis", "timeoutMillis", "zoneId");
            if (preset.rule() != null) {
                assertThat(preset.rule().linkSelector()).isNotBlank();
                assertThat(preset.rule().titleSelector()).isNotBlank();
                assertThat(preset.rule().contentSelector()).isNotBlank();
                assertThat(preset.rule().urlPattern()).isNotBlank();
            }
        });
    }
}
