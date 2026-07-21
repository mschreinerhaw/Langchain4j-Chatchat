package com.chatchat.mcpserver.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsCollectionTemplateCatalogTest {
    @Test
    void providesReusableWebsiteIndependentCollectionWorkflows() {
        var templates = new NewsCollectionTemplateCatalog().templates();

        assertThat(templates).extracting(NewsCollectionTemplateCatalog.CollectionTemplate::code)
            .containsExactly("disclosure_list_detail", "news_list_detail", "realtime_flash_cursor",
                "document_announcement_list", "rss_feed")
            .doesNotHaveDuplicates();
        assertThat(templates.get(0).sourceType()).isEqualTo("WEB_LIST");
        assertThat(templates.get(0).rule().linkSelector()).contains("announcement", "disclosure");
        assertThat(templates.get(0).configuration()).containsEntry("templateVersion", 1);
        assertThat(templates.get(0).workflow()).contains("提取二级正文", "下载并解析公告附件");
        assertThat(templates).filteredOn(template -> "realtime_flash_cursor".equals(template.code()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.sourceType()).isEqualTo("STRUCTURED_FLASH");
                assertThat(template.scheduleCron()).isEqualTo("0 */2 * * * *");
                assertThat(template.configuration()).containsEntry("templateVersion", 1)
                    .containsKey("request").containsKey("response").containsKey("mapping");
                assertThat(template.workflow()).contains("按持久化游标断点续采");
            });
        assertThat(templates).allSatisfy(template -> {
            assertThat(template.name()).isNotBlank();
            assertThat(template.description()).isNotBlank();
            assertThat(template.notes()).isNotEmpty();
        });
    }
}
