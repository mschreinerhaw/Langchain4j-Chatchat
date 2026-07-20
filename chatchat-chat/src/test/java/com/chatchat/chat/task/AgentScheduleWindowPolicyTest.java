package com.chatchat.chat.task;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScheduleWindowPolicyTest {

    private final AgentScheduleWindowPolicy policy = new AgentScheduleWindowPolicy();

    @Test
    void disabledWindowAllowsEveryInstant() {
        ScheduledTaskEntity entity = schedule(false, null, null);

        assertThat(policy.allows(entity, Instant.parse("2026-07-20T00:00:00Z"))).isTrue();
    }

    @Test
    void regularWindowIncludesStartAndExcludesEnd() {
        ScheduledTaskEntity entity = schedule(true, "09:00", "12:00");

        assertThat(policy.allows(entity, Instant.parse("2026-07-20T01:00:00Z"))).isTrue();
        assertThat(policy.allows(entity, Instant.parse("2026-07-20T03:59:59Z"))).isTrue();
        assertThat(policy.allows(entity, Instant.parse("2026-07-20T04:00:00Z"))).isFalse();
        assertThat(policy.allows(entity, Instant.parse("2026-07-20T00:59:59Z"))).isFalse();
    }

    @Test
    void overnightWindowAllowsBothSidesOfMidnight() {
        ScheduledTaskEntity entity = schedule(true, "22:00", "02:00");

        assertThat(policy.allows(entity, Instant.parse("2026-07-20T14:00:00Z"))).isTrue();
        assertThat(policy.allows(entity, Instant.parse("2026-07-20T17:00:00Z"))).isTrue();
        assertThat(policy.allows(entity, Instant.parse("2026-07-20T18:00:00Z"))).isFalse();
        assertThat(policy.allows(entity, Instant.parse("2026-07-20T06:00:00Z"))).isFalse();
    }

    @Test
    void findsNextWindowStartInConfiguredZone() {
        ScheduledTaskEntity daytime = schedule(true, "09:00", "12:00");
        ScheduledTaskEntity overnight = schedule(true, "22:00", "02:00");

        assertThat(policy.nextWindowStart(daytime, Instant.parse("2026-07-20T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-07-20T01:00:00Z"));
        assertThat(policy.nextWindowStart(daytime, Instant.parse("2026-07-20T05:00:00Z")))
            .isEqualTo(Instant.parse("2026-07-21T01:00:00Z"));
        assertThat(policy.nextWindowStart(overnight, Instant.parse("2026-07-20T06:00:00Z")))
            .isEqualTo(Instant.parse("2026-07-20T14:00:00Z"));
    }

    private ScheduledTaskEntity schedule(boolean enabled, String start, String end) {
        ScheduledTaskEntity entity = new ScheduledTaskEntity();
        entity.setScheduleWindowEnabled(enabled);
        entity.setScheduleWindowStart(start);
        entity.setScheduleWindowEnd(end);
        entity.setZoneId("Asia/Shanghai");
        return entity;
    }
}
