package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.compliance.RobotsComplianceReport;
import com.chatchat.runtime.news.compliance.RobotsTxtComplianceService;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.source.NewsSourceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewsCollectionServiceTest {
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
