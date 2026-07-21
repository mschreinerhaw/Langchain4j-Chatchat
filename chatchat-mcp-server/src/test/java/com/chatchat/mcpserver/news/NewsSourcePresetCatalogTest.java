package com.chatchat.mcpserver.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsSourcePresetCatalogTest {
    @Test
    void providesSafeDisabledNewsPresetsIncludingExchangeHomeSnapshots() {
        var presets = new NewsSourcePresetCatalog().presets();
        assertThat(presets).extracting(NewsSourcePresetCatalog.Preset::code).containsExactly(
            "sse_home", "szse_home", "hkex_home", "csindex_home", "sse_market_data", "szse_market_data", "sse_etf_scale", "szse_etf_scale", "three_market_overview", "szse_fund_etf_announcements", "szse_margin_business_announcements",
            "szse_auction_public_information", "szse_listing_disclosure", "eastmoney_finance", "eastmoney_724", "stcn_quick_news", "stcn_disclosures_today", "jin10_market_flash", "jin10_important_events", "cls_telegraph", "sse_announcements",
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
                    "announcementStaticBaseUrl", "marketUrlTemplate", "marketCodes", "detailSelector");
                assertThat(preset.source().configuration()).containsEntry("presetVersion", 3);
                assertThat(preset.source().configuration().get("marketCodes")).asList()
                    .containsExactly("399001", "399006", "399330", "399673");
                assertThat(preset.description()).contains("深证成指", "创业板指", "深证100", "创业板50");
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
        assertThat(presets).filteredOn(preset -> "hkex_home".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("HKEX_HOME");
                assertThat(preset.source().configuration()).containsEntry("presetVersion", 1)
                    .containsEntry("disclosureItemLimit", 100)
                    .containsKeys("rssFeeds", "disclosureUrl", "disclosureBaseUrl", "attachmentAllowedDomains");
                assertThat(preset.source().configuration().get("rssFeeds")).asList().hasSize(3);
                assertThat(preset.description()).contains("新闻稿", "监管公告", "市场通讯", "上市公司公告");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "csindex_home".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("CSINDEX_HOME");
                assertThat(preset.source().configuration()).containsEntry("presetVersion", 1)
                    .containsEntry("legalRisk", true)
                    .containsKeys("indexCodes", "indexSeriesUrl", "peHistoryUrlTemplate", "legalDisclaimer");
                assertThat(preset.source().configuration().get("indexCodes")).asList()
                    .containsExactly("000300", "000001", "000680", "000016", "000688");
                assertThat(preset.description()).contains("沪深300", "上证指数", "科创综指", "上证50", "科创50", "滚动市盈率");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "EXCHANGE_MARKET_DATA".equals(preset.source().sourceType()))
            .hasSize(5);
        assertThat(presets).filteredOn(preset -> "EXCHANGE_MARKET_DATA".equals(preset.source().sourceType())
                && preset.source().configuration().get("mode") == null)
            .hasSize(2).allSatisfy(preset -> {
                assertThat(preset.source().configuration()).containsEntry("presetVersion", 1)
                    .containsEntry("legalRisk", true)
                    .containsKeys("provider", "marginApiUrl", "legalDisclaimer");
                assertThat(preset.description()).contains("融资融券", "分红");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "FUND_SCALE".equals(preset.source().configuration().get("mode")))
            .hasSize(2).allSatisfy(preset -> {
                assertThat(preset.source().configuration()).containsKeys("fundScaleApiUrl", "legalDisclaimer");
                assertThat(preset.description()).contains("ETF规模", "万份", "全部");
            });
        assertThat(presets).filteredOn(preset -> "three_market_overview".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().configuration())
                    .containsEntry("mode", "THREE_MARKET_OVERVIEW")
                    .containsEntry("legalRisk", true)
                    .containsKeys("marketHighlightsApiUrl", "legalDisclaimer");
                assertThat(preset.description()).contains("香港", "上海", "深圳", "总市值", "成交金额");
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
                    .containsEntry("presetVersion", 3)
                    .containsEntry("itemLimit", 20)
                    .containsEntry("maxPagesPerRun", 200)
                    .containsEntry("initialBackfillHours", 24)
                    .containsEntry("minimumContentChars", 1)
                    .containsKeys("request", "response", "mapping", "compliance");
                assertThat(preset.source().sourceType()).isEqualTo("STRUCTURED_FLASH");
                assertThat(preset.description()).contains("断点续采");
            });
        assertThat(presets).filteredOn(preset -> "sse_bond_announcements".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("SSE_ANNOUNCEMENTS");
                assertThat(preset.source().configuration().get("feeds")).asList().hasSize(5);
                assertThat(preset.description()).contains("最新一页", "二级");
            });
        assertThat(presets).filteredOn(preset -> preset.code().startsWith("sse_")).hasSize(13)
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
                assertThat(preset.source().sourceType()).isEqualTo("STRUCTURED_FLASH");
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
        assertThat(presets).filteredOn(preset -> "eastmoney_724".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("STRUCTURED_FLASH");
                assertThat(preset.source().entryUrl()).isEqualTo("https://kuaixun.eastmoney.com/");
                assertThat(preset.source().configuration())
                    .containsEntry("legalRisk", true)
                    .containsKeys("request", "response", "mapping", "compliance", "maxPagesPerRun");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "stcn_quick_news".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("STRUCTURED_FLASH");
                assertThat(preset.source().entryUrl()).isEqualTo("https://www.stcn.com/article/list/kx.html");
                assertThat(preset.source().configuration())
                    .containsEntry("legalRisk", true)
                    .containsKeys("request", "response", "mapping", "compliance", "initialState");
                assertThat(preset.source().configuration().get("request")).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("signer", "STCN_WEB");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "stcn_disclosures_today".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("STCN_DISCLOSURES");
                assertThat(preset.source().configuration())
                    .containsEntry("pageSize", 20)
                    .containsEntry("legalRisk", true)
                    .containsKeys("apiUrl", "sections", "attachmentAllowedDomains", "legalDisclaimer");
                assertThat(preset.source().configuration().get("sections")).asList().hasSize(12);
                assertThat(preset.description()).contains("当前第一页", "当天");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "jin10_market_flash".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("STRUCTURED_FLASH");
                assertThat(preset.source().entryUrl()).isEqualTo("https://www.jin10.com/");
                assertThat(preset.source().configuration())
                    .containsEntry("legalRisk", true)
                    .containsKeys("request", "response", "mapping", "compliance", "initialState");
                assertThat(preset.source().configuration().get("mapping")).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP).containsKey("skipWhen");
                assertThat(preset.rule()).isNull();
            });
        assertThat(presets).filteredOn(preset -> "jin10_important_events".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("STRUCTURED_FLASH");
                assertThat(preset.source().configuration())
                    .containsEntry("snapshotMode", true)
                    .containsEntry("legalRisk", true)
                    .containsKeys("request", "response", "mapping", "compliance");
                assertThat(preset.source().entryUrl()).contains("jin10_important_news.html");
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
            assertThat(preset.source().collectionDescription()).isEqualTo(preset.description()).isNotBlank();
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
