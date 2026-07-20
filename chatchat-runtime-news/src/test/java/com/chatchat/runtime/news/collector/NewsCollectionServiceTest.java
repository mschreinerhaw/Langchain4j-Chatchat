package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.compliance.RobotsComplianceReport;
import com.chatchat.runtime.news.compliance.RobotsTxtComplianceService;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.source.NewsSourceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewsCollectionServiceTest {
    @Test
    void passesPersistedCursorToCollectorAndSavesNextCursor() {
        NewsSourceService sources = mock(NewsSourceService.class);
        RobotsTxtComplianceService robots = mock(RobotsTxtComplianceService.class);
        NewsCollector collector = mock(NewsCollector.class);
        NewsSource source = new NewsSource(8L, "cursor", "游标源", NewsSourceType.CLS_TELEGRAPH,
            "https://example.test/feed", "example.test", Map.of(), Map.of(), true);
        when(sources.requireEnabled(8L)).thenReturn(source);
        when(sources.lastCursor(8L)).thenReturn("old:100");
        when(robots.check(source)).thenReturn(new RobotsComplianceReport(true, "ALLOWED",
            source.entryUrl(), "https://example.test/robots.txt", 200, null, "允许", 1, Instant.now()));
        when(collector.supports(NewsSourceType.CLS_TELEGRAPH)).thenReturn(true);
        when(collector.collect(eq(source), any())).thenAnswer(invocation -> {
            com.chatchat.runtime.news.model.NewsCollectContext context = invocation.getArgument(1);
            assertThat(context.lastCursor()).isEqualTo("old:100");
            return new NewsCollectResult(context.executionId(), 8L, 1, 1, 0, 0, 0, null, "new:200");
        });

        NewsCollectionService service = new NewsCollectionService(sources, List.of(collector), Runnable::run, robots);
        var result = service.collect(8L, "cursor-execution");

        assertThat(result.nextCursor()).isEqualTo("new:200");
        verify(sources).markCollected(8L, "new:200");
    }

    @Test
    void blocksCollectorWhenRobotsPreflightFails() {
        NewsSourceService sources = mock(NewsSourceService.class);
        RobotsTxtComplianceService robots = mock(RobotsTxtComplianceService.class);
        NewsCollector collector = mock(NewsCollector.class);
        NewsSource source = new NewsSource(7L, "blocked", "受限网站", NewsSourceType.API,
            "https://example.test/private", "example.test", Map.of(), Map.of(), true);
        when(sources.requireEnabled(7L)).thenReturn(source);
        when(robots.check(source)).thenReturn(new RobotsComplianceReport(false, "DISALLOWED",
            source.entryUrl(), "https://example.test/robots.txt", 200, "/private",
            "机器人协议检测未通过：禁止访问。", 1, Instant.now()));
        NewsCollectionService service = new NewsCollectionService(sources, List.of(collector), Runnable::run, robots);

        var result = service.collect(7L, "test-execution");

        assertThat(result.failedCount()).isOne();
        assertThat(result.robotsAllowed()).isFalse();
        assertThat(result.robotsStatus()).isEqualTo("DISALLOWED");
        assertThat(result.errorMessage()).contains("机器人协议检测未通过");
        verifyNoInteractions(collector);
    }
}
