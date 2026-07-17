package com.chatchat.runtime.news.normalize;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class PublishTimeParser {

    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    );

    public Instant parse(String value, String zoneId) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        ZoneId zone = ZoneId.of(zoneId == null || zoneId.isBlank() ? "Asia/Shanghai" : zoneId);
        for (DateTimeFormatter formatter : LOCAL_FORMATS) {
            try {
                return LocalDateTime.parse(text, formatter).atZone(zone).toInstant();
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
