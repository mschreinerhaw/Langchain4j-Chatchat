package com.chatchat.mcpserver.news;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class NewsExtractionPatternCatalogTest {
    @Test
    void providesCompilablePatternsForNewsPagesAndOfficeAttachments() {
        var presets = new NewsExtractionPatternCatalog().presets();

        assertThat(presets).extracting(NewsExtractionPatternCatalog.PatternPreset::code).doesNotHaveDuplicates();
        assertThat(presets).allSatisfy(preset -> Pattern.compile(preset.regex()));
        assertThat(regex(presets, "common_news_detail").matcher("https://example.com/news/detail/123").matches()).isTrue();
        assertThat(regex(presets, "common_announcement").matcher("https://example.com/disclosure/notice/123").matches()).isTrue();
        assertThat(regex(presets, "document_attachment").matcher("https://example.com/files/report.docx?download=1").matches()).isTrue();
        assertThat(regex(presets, "document_attachment").matcher("https://example.com/files/report.xlsx").matches()).isTrue();
    }

    private Pattern regex(java.util.List<NewsExtractionPatternCatalog.PatternPreset> presets, String code) {
        return Pattern.compile(presets.stream().filter(item -> code.equals(item.code())).findFirst().orElseThrow().regex());
    }
}
