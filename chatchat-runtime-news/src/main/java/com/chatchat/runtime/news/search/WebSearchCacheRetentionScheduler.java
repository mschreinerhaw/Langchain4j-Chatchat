package com.chatchat.runtime.news.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WebSearchCacheRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(WebSearchCacheRetentionScheduler.class);
    private final WebSearchCache cache;

    public WebSearchCacheRetentionScheduler(WebSearchCache cache) {
        this.cache = cache;
    }

    @Scheduled(cron = "${chatchat.runtime.news.web-search.cache.cleanup-cron:0 45 0 * * *}",
        zone = "${chatchat.runtime.news.web-search.cache.zone-id:Asia/Shanghai}")
    public void cleanup() {
        if (!cache.enabled()) return;
        try {
            List<String> deleted = cache.deleteExpiredIndices();
            if (!deleted.isEmpty()) log.info("Deleted expired web-search cache indices: {}", deleted);
        } catch (Exception ex) {
            log.error("Failed to clean expired web-search cache indices", ex);
        }
    }
}
