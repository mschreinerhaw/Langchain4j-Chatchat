package com.chatchat.mcpserver.database;

import com.chatchat.mcpserver.search.McpTemplateLuceneIndexService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DatabaseQueryPublicationServiceTest {

    @Test
    void queuesEnabledTemplateForIncrementalPublicationRefresh() {
        DatabaseQueryMcpToolPublisher publisher = mock(DatabaseQueryMcpToolPublisher.class);
        McpTemplateLuceneIndexService indexService = mock(McpTemplateLuceneIndexService.class);
        DatabaseQueryPublicationService service = new DatabaseQueryPublicationService(
            publisher, indexService, Runnable::run);
        DatabaseQueryConfig saved = new DatabaseQueryConfig();
        saved.setId("query-1");
        saved.setEnabled(true);

        service.refreshAsync(saved);

        verify(publisher).refresh();
        verify(indexService).upsertDatabaseQueryTemplates(java.util.List.of(saved));
    }

    @Test
    void queuesFullRefreshWhenTemplateIsDisabled() {
        DatabaseQueryMcpToolPublisher publisher = mock(DatabaseQueryMcpToolPublisher.class);
        McpTemplateLuceneIndexService indexService = mock(McpTemplateLuceneIndexService.class);
        DatabaseQueryPublicationService service = new DatabaseQueryPublicationService(
            publisher, indexService, Runnable::run);
        DatabaseQueryConfig saved = new DatabaseQueryConfig();
        saved.setId("query-1");
        saved.setEnabled(false);

        service.refreshAsync(saved);

        verify(publisher).refresh();
        verify(indexService).refreshDatabaseQueryTemplateIndex();
    }
}
