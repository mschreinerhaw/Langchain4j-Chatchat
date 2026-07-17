package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.source.NewsSourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Executes enabled, preconfigured news sources according to their stored cron expression. */
@Slf4j
@Component
public class NewsScheduleCoordinator {
    private final NewsSourceRepository repository;
    private final NewsCollectionService collectionService;
    private final Map<Long, ScheduleState> schedules = new ConcurrentHashMap<>();

    public NewsScheduleCoordinator(NewsSourceRepository repository, NewsCollectionService collectionService) {
        this.repository = repository;
        this.collectionService = collectionService;
    }

    @Scheduled(fixedDelayString = "${chatchat.runtime.news.schedule-scan-millis:30000}")
    public void dispatchDueSources() {
        ZonedDateTime now = ZonedDateTime.now();
        var enabled = repository.findByEnabledTrue();
        var activeIds = enabled.stream().map(source -> source.getId()).collect(java.util.stream.Collectors.toSet());
        schedules.keySet().removeIf(id -> !activeIds.contains(id));
        for (var source : enabled) {
            String cronText = source.getScheduleCron();
            if (cronText == null || cronText.isBlank()) continue;
            try {
                ScheduleState state = schedules.get(source.getId());
                if (state == null || !state.cron().equals(cronText)) {
                    CronExpression cron = CronExpression.parse(cronText);
                    state = new ScheduleState(cronText, cron, cron.next(now.minusSeconds(1)));
                    schedules.put(source.getId(), state);
                }
                if (state.next() != null && !state.next().isAfter(now)) {
                    collectionService.collectAsync(source.getId()).whenComplete((result, error) -> {
                        if (error != null) log.warn("Scheduled news collection failed sourceId={}", source.getId(), error);
                    });
                    schedules.put(source.getId(), new ScheduleState(cronText, state.expression(), state.expression().next(now)));
                }
            } catch (Exception ex) {
                log.warn("Invalid news schedule sourceId={} cron={}: {}", source.getId(), cronText, ex.getMessage());
            }
        }
    }

    private record ScheduleState(String cron, CronExpression expression, ZonedDateTime next) { }
}
