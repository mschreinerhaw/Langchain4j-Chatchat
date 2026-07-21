package com.chatchat.runtime.news.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Creates the new calendar-day news index at midnight before scheduled collection begins. */
@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news.open-search", name = "enabled", havingValue = "true")
public class NewsDailyIndexRebuildScheduler {
    private static final Logger log = LoggerFactory.getLogger(NewsDailyIndexRebuildScheduler.class);
    private final OpenSearchNewsDocumentStore store;

    public NewsDailyIndexRebuildScheduler(OpenSearchNewsDocumentStore store) {
        this.store = store;
    }

    @Scheduled(cron = "${chatchat.runtime.news.open-search.rebuild-cron:0 0 0 * * *}",
        zone = "${chatchat.runtime.news.open-search.zone-id:Asia/Shanghai}")
    public void rebuild() {
        long startedAt = System.currentTimeMillis();
        try {
            String index = store.ensureDailyIndex();
            log.info("news_daily_index_rebuild_completed index={} durationMs={}",
                index, System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.error("news_daily_index_rebuild_failed", ex);
        }
    }
}
