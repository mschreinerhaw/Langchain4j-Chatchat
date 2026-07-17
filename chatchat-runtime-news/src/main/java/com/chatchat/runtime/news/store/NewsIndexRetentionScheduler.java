package com.chatchat.runtime.news.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news.open-search", name = "enabled", havingValue = "true")
public class NewsIndexRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(NewsIndexRetentionScheduler.class);
    private final OpenSearchNewsDocumentStore store;

    public NewsIndexRetentionScheduler(OpenSearchNewsDocumentStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupAfterStartup() {
        cleanup();
    }

    @Scheduled(cron = "${chatchat.runtime.news.open-search.retention-cron:0 15 0 * * *}",
        zone = "${chatchat.runtime.news.open-search.zone-id:Asia/Shanghai}")
    public void cleanup() {
        try {
            store.ensureDailyIndex();
            List<String> deleted = store.deleteExpiredIndices();
            if (!deleted.isEmpty()) log.info("Deleted expired news indices: {}", deleted);
        } catch (Exception ex) {
            log.error("Failed to clean expired news indices", ex);
        }
    }
}
