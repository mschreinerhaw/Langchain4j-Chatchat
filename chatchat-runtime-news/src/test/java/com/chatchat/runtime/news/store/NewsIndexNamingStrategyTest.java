package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class NewsIndexNamingStrategyTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void createsOneIndexPerShanghaiCalendarDayAndReadsOnlySevenDays() {
        NewsRuntimeProperties.OpenSearch properties = new NewsRuntimeProperties.OpenSearch();
        properties.setIndexName("runtime-news");
        properties.setRetentionDays(7);
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T04:00:00Z"), ZONE);
        NewsIndexNamingStrategy strategy = new NewsIndexNamingStrategy(properties, clock);

        assertThat(strategy.writeIndex(Instant.parse("2026-07-16T16:01:00Z")))
            .isEqualTo("runtime-news-2026.07.17");
        assertThat(strategy.readableIndices()).containsExactly(
            "runtime-news-2026.07.17", "runtime-news-2026.07.16", "runtime-news-2026.07.15",
            "runtime-news-2026.07.14", "runtime-news-2026.07.13", "runtime-news-2026.07.12",
            "runtime-news-2026.07.11");
        assertThat(strategy.isExpired("runtime-news-2026.07.10")).isTrue();
        assertThat(strategy.isExpired("runtime-news-2026.07.11")).isFalse();
        assertThat(strategy.isExpired("runtime-news-backup")).isFalse();
        assertThat(strategy.isExpired("another-index-2026.07.10")).isFalse();
    }
}
