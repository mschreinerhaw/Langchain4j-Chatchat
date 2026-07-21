package com.chatchat.mcpserver.database;

import com.chatchat.mcpserver.search.McpTemplateLuceneIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class DatabaseQueryPublicationService {

    private final DatabaseQueryMcpToolPublisher publisher;
    private final McpTemplateLuceneIndexService templateIndexService;
    private final Executor executor;

    public DatabaseQueryPublicationService(DatabaseQueryMcpToolPublisher publisher,
                                           McpTemplateLuceneIndexService templateIndexService,
                                           @Qualifier("databaseQueryPublicationExecutor") Executor executor) {
        this.publisher = publisher;
        this.templateIndexService = templateIndexService;
        this.executor = executor;
    }

    public void refreshAsync() {
        submit(null, true);
    }

    public void refreshAsync(DatabaseQueryConfig saved) {
        submit(saved, saved == null || !saved.isEnabled());
    }

    private void submit(DatabaseQueryConfig saved, boolean fullIndexRefresh) {
        try {
            executor.execute(() -> refresh(saved, fullIndexRefresh));
        } catch (RuntimeException ex) {
            log.error("Database query publication refresh could not be queued databaseQueryId={}: {}",
                saved == null ? null : saved.getId(), ex.getMessage(), ex);
        }
    }

    private void refresh(DatabaseQueryConfig saved, boolean fullIndexRefresh) {
        try {
            publisher.refresh();
            if (fullIndexRefresh) {
                templateIndexService.refreshDatabaseQueryTemplateIndex();
            } else {
                templateIndexService.upsertDatabaseQueryTemplates(List.of(saved));
            }
        } catch (Exception ex) {
            log.error("Database query publication refresh failed databaseQueryId={}: {}",
                saved == null ? null : saved.getId(), ex.getMessage(), ex);
        }
    }
}
