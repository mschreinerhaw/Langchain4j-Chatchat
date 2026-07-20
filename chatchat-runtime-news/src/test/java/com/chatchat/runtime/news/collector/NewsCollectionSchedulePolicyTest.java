package com.chatchat.runtime.news.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewsCollectionSchedulePolicyTest {
    private final NewsCollectionSchedulePolicy policy = new NewsCollectionSchedulePolicy(new ObjectMapper());

    @Test
    void allowsCollectionWithoutAnEnabledWindow() {
        assertThat(policy.allowsAutomaticCollection("{\"zoneId\":\"Asia/Shanghai\"}",
                Instant.parse("2026-07-20T00:00:00Z"))).isTrue();
    }

    @Test
    void usesAnInclusiveStartAndExclusiveEnd() {
        String configuration = "{\"zoneId\":\"Asia/Shanghai\",\"scheduleWindowEnabled\":true,"
                + "\"scheduleWindowStart\":\"09:00\",\"scheduleWindowEnd\":\"12:00\"}";

        assertThat(policy.allowsAutomaticCollection(configuration, Instant.parse("2026-07-20T01:00:00Z"))).isTrue();
        assertThat(policy.allowsAutomaticCollection(configuration, Instant.parse("2026-07-20T03:59:59Z"))).isTrue();
        assertThat(policy.allowsAutomaticCollection(configuration, Instant.parse("2026-07-20T04:00:00Z"))).isFalse();
        assertThat(policy.allowsAutomaticCollection(configuration, Instant.parse("2026-07-20T00:59:59Z"))).isFalse();
    }

    @Test
    void supportsAWindowThatSpansMidnight() {
        String configuration = "{\"zoneId\":\"Asia/Shanghai\",\"scheduleWindowEnabled\":true,"
                + "\"scheduleWindowStart\":\"22:00\",\"scheduleWindowEnd\":\"02:00\"}";

        assertThat(policy.allowsAutomaticCollection(configuration, Instant.parse("2026-07-20T15:00:00Z"))).isTrue();
        assertThat(policy.allowsAutomaticCollection(configuration, Instant.parse("2026-07-20T17:00:00Z"))).isTrue();
        assertThat(policy.allowsAutomaticCollection(configuration, Instant.parse("2026-07-20T19:00:00Z"))).isFalse();
    }

    @Test
    void rejectsAnEmptyWindow() {
        String configuration = "{\"scheduleWindowEnabled\":true,"
                + "\"scheduleWindowStart\":\"09:00\",\"scheduleWindowEnd\":\"09:00\"}";

        assertThatThrownBy(() -> policy.allowsAutomaticCollection(configuration, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
