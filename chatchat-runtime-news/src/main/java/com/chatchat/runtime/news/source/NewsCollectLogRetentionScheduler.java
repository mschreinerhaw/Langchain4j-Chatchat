package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news.collect-log", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class NewsCollectLogRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectLogRetentionScheduler.class);

    private final NewsCollectLogRetentionService retentionService;
    private final NewsRuntimeProperties properties;

    public NewsCollectLogRetentionScheduler(NewsCollectLogRetentionService retentionService,
                                            NewsRuntimeProperties properties) {
        this.retentionService = retentionService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupAfterStartup() {
        cleanup();
    }

    @Scheduled(cron = "${chatchat.runtime.news.collect-log.cleanup-cron:0 30 0 * * *}",
        zone = "${chatchat.runtime.news.collect-log.zone-id:Asia/Shanghai}")
    public void cleanup() {
        NewsRuntimeProperties.CollectLog config = properties.getCollectLog();
        int retentionDays = Math.max(1, config.getRetentionDays());
        int batchSize = Math.max(1, Math.min(config.getCleanupBatchSize(), 10_000));
        int maxBatches = Math.max(1, config.getMaxBatchesPerRun());
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = 0;
        try {
            for (int batch = 0; batch < maxBatches; batch++) {
                int current = retentionService.deleteExpiredBatch(cutoff, batchSize);
                deleted += current;
                if (current < batchSize) {
                    break;
                }
            }
            if (deleted > 0) {
                log.info("Deleted {} expired news collection log records older than {} days cutoff={}",
                    deleted, retentionDays, cutoff);
            }
        } catch (Exception ex) {
            log.error("Failed to clean news collection logs older than {} days cutoff={} deletedBeforeFailure={}",
                retentionDays, cutoff, deleted, ex);
        }
    }
}
