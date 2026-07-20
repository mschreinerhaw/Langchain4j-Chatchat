package com.chatchat.mcpserver.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsCollectionTemplateCatalogTest {
    @Test
    void providesReusableWebsiteIndependentCollectionWorkflows() {
        var templates = new NewsCollectionTemplateCatalog().templates();

        assertThat(templates).extracting(NewsCollectionTemplateCatalog.CollectionTemplate::code)
            .containsExactly("disclosure_list_detail", "news_list_detail", "document_announcement_list", "rss_feed")
            .doesNotHaveDuplicates();
        assertThat(templates.get(0).sourceType()).isEqualTo("WEB_LIST");
        assertThat(templates.get(0).rule().linkSelector()).contains("announcement", "disclosure");
        assertThat(templates.get(0).configuration()).containsEntry("templateVersion", 1);
        assertThat(templates.get(0).workflow()).contains("提取二级正文", "下载并解析公告附件");
        assertThat(templates).allSatisfy(template -> {
            assertThat(template.name()).isNotBlank();
            assertThat(template.description()).isNotBlank();
            assertThat(template.notes()).isNotEmpty();
        });
    }
}
