package com.chatchat.mcpserver.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsSourcePresetCatalogTest {
    @Test
    void providesSafeDisabledNewsPresetsIncludingExchangeHomeSnapshots() {
        var presets = new NewsSourcePresetCatalog().presets();
        assertThat(presets).extracting(NewsSourcePresetCatalog.Preset::code).containsExactly(
            "sse_home", "szse_home", "eastmoney_finance", "cls_telegraph", "sse_announcements",
            "szse_announcements", "cninfo_home", "cninfo_announcements");
        assertThat(presets).filteredOn(preset -> "EXCHANGE_HOME".equals(preset.source().sourceType()))
            .hasSize(2).allSatisfy(preset -> {
                assertThat(preset.rule()).isNull();
                assertThat(preset.source().configuration()).containsKeys("provider", "headlineSelector", "marketUrlTemplate", "marketCodes");
            });
        assertThat(presets).filteredOn(preset -> "szse_announcements".equals(preset.code())).singleElement()
            .satisfies(preset -> assertThat(preset.source().entryUrl()).isEqualTo("https://www.szse.cn/disclosure/listed/notice/"));
        assertThat(presets).filteredOn(preset -> "cls_telegraph".equals(preset.code())).singleElement()
            .satisfies(preset -> {
                assertThat(preset.source().sourceType()).isEqualTo("CLS_TELEGRAPH");
                assertThat(preset.rule()).isNull();
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
