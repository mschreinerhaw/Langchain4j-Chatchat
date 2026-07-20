package com.chatchat.runtime.news.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/** Applies the source time zone and optional daily collection window to automatic collection. */
@Component
public class NewsCollectionSchedulePolicy {
    static final String DEFAULT_ZONE_ID = "Asia/Shanghai";

    private final ObjectMapper objectMapper;

    public NewsCollectionSchedulePolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ZoneId zoneId(String configurationJson) {
        JsonNode configuration = readConfiguration(configurationJson);
        String configuredZone = configuration.path("zoneId").asText(DEFAULT_ZONE_ID).trim();
        return ZoneId.of(configuredZone.isEmpty() ? DEFAULT_ZONE_ID : configuredZone);
    }

    public boolean allowsAutomaticCollection(String configurationJson, Instant instant) {
        JsonNode configuration = readConfiguration(configurationJson);
        if (!configuration.path("scheduleWindowEnabled").asBoolean(false)) {
            return true;
        }

        LocalTime start = requiredTime(configuration, "scheduleWindowStart");
        LocalTime end = requiredTime(configuration, "scheduleWindowEnd");
        if (start.equals(end)) {
            throw new IllegalArgumentException("scheduleWindowStart and scheduleWindowEnd must be different");
        }

        String configuredZone = configuration.path("zoneId").asText(DEFAULT_ZONE_ID).trim();
        ZoneId zone = ZoneId.of(configuredZone.isEmpty() ? DEFAULT_ZONE_ID : configuredZone);
        LocalTime current = instant.atZone(zone).toLocalTime();
        if (start.isBefore(end)) {
            return !current.isBefore(start) && current.isBefore(end);
        }
        // A window such as 22:00-02:00 spans midnight.
        return !current.isBefore(start) || current.isBefore(end);
    }

    private LocalTime requiredTime(JsonNode configuration, String field) {
        String value = configuration.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required when schedule window is enabled");
        }
        return LocalTime.parse(value);
    }

    private JsonNode readConfiguration(String configurationJson) {
        if (configurationJson == null || configurationJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(configurationJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid news source configuration JSON", ex);
        }
    }
}
