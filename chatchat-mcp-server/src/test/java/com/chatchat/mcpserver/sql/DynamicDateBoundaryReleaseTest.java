package com.chatchat.mcpserver.sql;

import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DynamicDateBoundaryReleaseTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaries")
    void resolvesCalendarBoundariesFromRuntimeClock(String scenario, Instant instant, ZoneId zone,
                                                     String today, String monthStart, String monthEnd) {
        DynamicDateParamService service = new DynamicDateParamService(
            mock(DynamicJdbcDriverLoader.class), Clock.fixed(instant, zone));

        Map<String, Object> resolved = service.enrichParameters(Map.of(), null,
            "select '${today}', '${month_start}', '${month_end}'");

        assertThat(resolved).containsEntry("today", today)
            .containsEntry("natural_date", today)
            .containsEntry("month_start", monthStart)
            .containsEntry("month_end", monthEnd);
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
            Arguments.of("leap-day", Instant.parse("2024-02-29T12:00:00Z"), ZoneId.of("Asia/Shanghai"),
                "20240229", "20240201", "20240229"),
            Arguments.of("utc-year-to-shanghai-year", Instant.parse("2025-12-31T16:00:00Z"), ZoneId.of("Asia/Shanghai"),
                "20260101", "20260101", "20260131"),
            Arguments.of("dst-spring-forward", Instant.parse("2026-03-08T07:30:00Z"), ZoneId.of("America/New_York"),
                "20260308", "20260301", "20260331")
        );
    }
}
