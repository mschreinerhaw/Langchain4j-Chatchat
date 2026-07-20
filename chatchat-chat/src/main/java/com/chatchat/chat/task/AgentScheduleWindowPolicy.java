package com.chatchat.chat.task;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Evaluates an optional daily execution window for automatic Agent schedules. */
@Component
public class AgentScheduleWindowPolicy {
    static final String DEFAULT_ZONE_ID = "Asia/Shanghai";

    public boolean allows(ScheduledTaskEntity entity, Instant instant) {
        if (!Boolean.TRUE.equals(entity.getScheduleWindowEnabled())) return true;
        LocalTime start = LocalTime.parse(entity.getScheduleWindowStart());
        LocalTime end = LocalTime.parse(entity.getScheduleWindowEnd());
        LocalTime current = instant.atZone(zoneId(entity)).toLocalTime();
        if (start.isBefore(end)) {
            return !current.isBefore(start) && current.isBefore(end);
        }
        return !current.isBefore(start) || current.isBefore(end);
    }

    public Instant nextWindowStart(ScheduledTaskEntity entity, Instant instant) {
        ZoneId zone = zoneId(entity);
        ZonedDateTime current = instant.atZone(zone);
        LocalTime start = LocalTime.parse(entity.getScheduleWindowStart());
        LocalTime end = LocalTime.parse(entity.getScheduleWindowEnd());
        LocalDate date = current.toLocalDate();
        LocalTime time = current.toLocalTime();
        if (start.isBefore(end)) {
            if (!time.isBefore(start)) date = date.plusDays(1);
        } else if (!time.isBefore(start)) {
            date = date.plusDays(1);
        }
        return date.atTime(start).atZone(zone).toInstant();
    }

    public ZoneId zoneId(ScheduledTaskEntity entity) {
        String configured = entity.getZoneId();
        return ZoneId.of(configured == null || configured.isBlank() ? DEFAULT_ZONE_ID : configured.trim());
    }
}
