package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.source.NewsSourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Executes enabled, preconfigured news sources according to their stored cron expression. */
@Slf4j
@Component
public class NewsScheduleCoordinator {
    private final NewsSourceRepository repository;
    private final NewsCollectionService collectionService;
    private final NewsCollectionSchedulePolicy schedulePolicy;
    private final Map<Long, ScheduleState> schedules = new ConcurrentHashMap<>();

    public NewsScheduleCoordinator(NewsSourceRepository repository, NewsCollectionService collectionService,
                                   NewsCollectionSchedulePolicy schedulePolicy) {
        this.repository = repository;
        this.collectionService = collectionService;
        this.schedulePolicy = schedulePolicy;
    }

    @Scheduled(fixedDelayString = "${chatchat.runtime.news.schedule-scan-millis:30000}")
    public void dispatchDueSources() {
        Instant instant = Instant.now();
        var enabled = repository.findByEnabledTrue();
        var activeIds = enabled.stream().map(source -> source.getId()).collect(java.util.stream.Collectors.toSet());
        schedules.keySet().removeIf(id -> !activeIds.contains(id));
        for (var source : enabled) {
            String cronText = source.getScheduleCron();
            if (cronText == null || cronText.isBlank()) continue;
            try {
                ZoneId zoneId = schedulePolicy.zoneId(source.getConfigurationJson());
                ZonedDateTime now = instant.atZone(zoneId);
                ScheduleState state = schedules.get(source.getId());
                if (state == null || !state.cron().equals(cronText) || !state.zoneId().equals(zoneId)) {
                    CronExpression cron = CronExpression.parse(cronText);
                    state = new ScheduleState(cronText, zoneId, cron, cron.next(now.minusSeconds(1)));
                    schedules.put(source.getId(), state);
                }
                if (state.next() != null && !state.next().isAfter(now)) {
                    schedules.put(source.getId(), new ScheduleState(cronText, zoneId, state.expression(), state.expression().next(now)));
                    if (!schedulePolicy.allowsAutomaticCollection(source.getConfigurationJson(), instant)) {
                        log.debug("Skipping scheduled news collection outside configured window sourceId={}", source.getId());
                        continue;
                    }
                    collectionService.collectAsync(source.getId()).whenComplete((result, error) -> {
                        if (error != null) log.warn("Scheduled news collection failed sourceId={}", source.getId(), error);
                    });
                }
            } catch (Exception ex) {
                log.warn("Invalid news schedule sourceId={} cron={}: {}", source.getId(), cronText, ex.getMessage());
            }
        }
    }

    private record ScheduleState(String cron, ZoneId zoneId, CronExpression expression, ZonedDateTime next) { }
}
