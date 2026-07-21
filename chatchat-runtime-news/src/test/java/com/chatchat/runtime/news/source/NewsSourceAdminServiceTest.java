package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.model.NewsSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsSourceAdminServiceTest {

    @Test
    void exposesAndPersistsCollectionDescriptionWithoutAddingASchemaColumn() throws Exception {
        NewsSourceRepository sources = mock(NewsSourceRepository.class);
        NewsSourceRuleRepository rules = mock(NewsSourceRuleRepository.class);
        NewsCollectRecordRepository records = mock(NewsCollectRecordRepository.class);
        when(sources.findBySourceCode("sample_news")).thenReturn(Optional.empty());
        when(sources.save(any())).thenAnswer(invocation -> {
            NewsSourceEntity entity = invocation.getArgument(0);
            entity.setId(7L);
            return entity;
        });
        when(records.countBySourceId(7L)).thenReturn(0L);
        NewsSourceAdminService service = new NewsSourceAdminService(sources, rules, records, new ObjectMapper());

        var view = service.create(1L, new NewsSourceAdminService.NewsSourceUpsert(
            "sample_news", "Sample News", NewsSourceType.API, "https://example.com/news",
            "Collects today's first page and article bodies.", "example.com", "0 */10 * * * *", false,
            Map.of("timeoutMillis", 20000)));

        assertThat(view.collectionDescription()).isEqualTo("Collects today's first page and article bodies.");
        assertThat(view.configuration()).containsEntry(
            "collectionDescription", "Collects today's first page and article bodies.");
    }
}
