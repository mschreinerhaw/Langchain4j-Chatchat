package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.compliance.RobotsComplianceReport;
import com.chatchat.runtime.news.compliance.RobotsTxtComplianceService;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.source.NewsSourceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Internal/scheduler entry point. It is intentionally not exposed as an Agent tool. */
@Service
public class NewsCollectionService {
    private static final Logger log = LoggerFactory.getLogger(NewsCollectionService.class);
    private final NewsSourceService sourceService;
    private final List<NewsCollector> collectors;
    private final Executor executor;
    private final RobotsTxtComplianceService robotsCompliance;

    public NewsCollectionService(NewsSourceService sourceService, List<NewsCollector> collectors,
                                 @Qualifier("newsCollectorExecutor") Executor executor,
                                 RobotsTxtComplianceService robotsCompliance) {
        this.sourceService = sourceService;
        this.collectors = collectors;
        this.executor = executor;
        this.robotsCompliance = robotsCompliance;
    }

    public CompletableFuture<NewsCollectResult> collectAsync(Long sourceId) {
        String executionId = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> collect(sourceId, executionId), executor);
    }

    public NewsCollectResult collect(Long sourceId, String executionId) {
        long startedAt = System.currentTimeMillis();
        try {
            NewsSource source = sourceService.requireEnabled(sourceId);
            RobotsComplianceReport robots = robotsCompliance.check(source);
            if (!robots.allowed()) {
                log.warn("news_collect_blocked_by_robots executionId={} sourceId={} sourceCode={} targetUrl={} robotsUrl={} status={} rule={} detail={}",
                    executionId, source.id(), source.code(), robots.targetUrl(), robots.robotsUrl(), robots.status(),
                    robots.matchedRule(), robots.message());
                return new NewsCollectResult(executionId, source.id(), 0, 0, 0, 0, 1, robots.message(),
                    false, robots.status(), robots.robotsUrl(), null, null);
            }
            if (robots.overridden()) {
                log.warn("news_collect_robots_override executionId={} sourceId={} sourceCode={} targetUrl={} robotsUrl={} rule={} overrideUntil={} overrideReason={}",
                    executionId, source.id(), source.code(), robots.targetUrl(), robots.robotsUrl(),
                    robots.matchedRule(), robots.overrideUntil(), robots.overrideReason());
            }
            NewsCollector collector = collectors.stream().filter(item -> item.supports(source.sourceType()))
                .findFirst().orElseThrow(() -> new IllegalStateException("No collector for " + source.sourceType()));
            log.info("news_collect_started executionId={} sourceId={} sourceCode={} sourceType={} entryUrl={} collector={}",
                executionId, source.id(), source.code(), source.sourceType(), source.entryUrl(), collector.getClass().getSimpleName());
            NewsCollectResult result = collector.collect(source,
                new NewsCollectContext(executionId, Instant.now(), sourceService.lastCursor(sourceId)));
            if (result.failedCount() == 0) sourceService.markCollected(sourceId, result.nextCursor());
            log.info("news_collect_completed executionId={} sourceId={} sourceCode={} discovered={} accepted={} duplicates={} rejected={} failed={} durationMs={} error={}",
                executionId, source.id(), source.code(), result.discoveredCount(), result.acceptedCount(),
                result.duplicateCount(), result.rejectedCount(), result.failedCount(),
                System.currentTimeMillis() - startedAt, result.errorMessage());
            if (result.failedCount() > 0 && result.errorMessage() != null
                && result.errorMessage().contains("Dynamic page detected")) {
                log.warn("news_collect_diagnostic executionId={} sourceId={} sourceCode={} classification=DYNAMIC_PAGE action=USE_OFFICIAL_API_OR_DEDICATED_COLLECTOR detail={}",
                    executionId, source.id(), source.code(), result.errorMessage());
            }
            return result.withRobots(true, robots.status(), robots.robotsUrl(),
                robots.overrideReason(), robots.overrideUntil());
        } catch (RuntimeException ex) {
            log.error("news_collect_failed executionId={} sourceId={} durationMs={} error={}",
                executionId, sourceId, System.currentTimeMillis() - startedAt, ex.getMessage(), ex);
            throw ex;
        }
    }

    public RobotsComplianceReport checkRobots(Long sourceId) {
        return robotsCompliance.check(sourceService.require(sourceId));
    }
}
