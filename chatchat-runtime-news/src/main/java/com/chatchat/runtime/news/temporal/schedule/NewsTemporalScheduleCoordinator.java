package com.chatchat.runtime.news.temporal.schedule;

import com.chatchat.runtime.news.collector.schedule.NewsCollectionSchedulePolicy;
import com.chatchat.runtime.news.source.persistence.NewsSourceEntity;
import com.chatchat.runtime.news.source.persistence.NewsSourceRepository;
import com.chatchat.runtime.news.temporal.config.NewsTemporalProperties;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowCommand;
import com.chatchat.runtime.news.temporal.workflow.NewsCollectionWorkflow;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.common.RetryOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reconciles database news-source definitions into durable Temporal Schedules. */
@Slf4j
@Component
@ConditionalOnExpression("'${chatchat.runtime.news.temporal.enabled:true}' == 'true' && "
    + "'${chatchat.runtime.news.temporal.server-mode:embedded}' == 'external'")
public class NewsTemporalScheduleCoordinator {
    static final String SCHEDULE_PREFIX = "chatchat-news-source-";
    private static final String WORKFLOW_PREFIX = "chatchat-news-collection-";

    private final NewsSourceRepository repository;
    private final NewsCollectionSchedulePolicy schedulePolicy;
    private final ScheduleClient scheduleClient;
    private final NewsTemporalProperties properties;
    private final Map<String, String> appliedFingerprints = new ConcurrentHashMap<>();

    public NewsTemporalScheduleCoordinator(NewsSourceRepository repository,
                                           NewsCollectionSchedulePolicy schedulePolicy,
                                           ScheduleClient scheduleClient,
                                           NewsTemporalProperties properties) {
        this.repository = repository;
        this.schedulePolicy = schedulePolicy;
        this.scheduleClient = scheduleClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${chatchat.runtime.news.temporal.reconcile-delay-millis:30000}")
    public void reconcile() {
        try {
            List<NewsSourceEntity> sources = repository.findAll();
            Set<String> remoteIds = managedScheduleIds();
            Set<String> desiredIds = new HashSet<>();
            for (NewsSourceEntity source : sources) {
                if (source.getId() == null || source.getScheduleCron() == null
                    || source.getScheduleCron().isBlank()) {
                    continue;
                }
                String scheduleId = scheduleId(source.getId());
                desiredIds.add(scheduleId);
                String fingerprint;
                Schedule desired;
                try {
                    fingerprint = fingerprint(source);
                    desired = desiredSchedule(source);
                } catch (RuntimeException invalid) {
                    appliedFingerprints.remove(scheduleId);
                    pauseInvalidSchedule(scheduleId, source, invalid);
                    continue;
                }
                if (!remoteIds.contains(scheduleId)
                    || !fingerprint.equals(appliedFingerprints.get(scheduleId))) {
                    try {
                        upsert(scheduleId, source, desired);
                        appliedFingerprints.put(scheduleId, fingerprint);
                    } catch (RuntimeException unavailable) {
                        log.warn("news_temporal_schedule_upsert_failed scheduleId={} sourceId={} error={}",
                            scheduleId, source.getId(), unavailable.getMessage());
                    }
                }
            }
            removeStaleSchedules(desiredIds, remoteIds);
        } catch (RuntimeException failure) {
            log.warn("news_temporal_schedule_reconcile_failed error={}", failure.getMessage());
        }
    }

    Schedule desiredSchedule(NewsSourceEntity source) {
        String cron = temporalCron(source.getScheduleCron());
        String zoneId = schedulePolicy.zoneId(source.getConfigurationJson()).getId();
        var command = new NewsCollectionWorkflowCommand(source.getId(),
            properties.activityStartToCloseSeconds(), properties.activityMaximumAttempts());
        var action = ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType(NewsCollectionWorkflow.class)
            .setArguments(command)
            .setOptions(WorkflowOptions.newBuilder()
                .setWorkflowId(WORKFLOW_PREFIX + source.getId())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .setTaskQueue(properties.taskQueue())
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .setMemo(Map.of("newsSourceId", source.getId(),
                    "newsSourceCode", source.getSourceCode() == null ? "" : source.getSourceCode()))
                .build())
            .build();
        return Schedule.newBuilder()
            .setAction(action)
            .setSpec(ScheduleSpec.newBuilder()
                .setCronExpressions(List.of(cron))
                .setTimeZoneName(zoneId)
                .build())
            .setPolicy(SchedulePolicy.newBuilder()
                .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                .setCatchupWindow(Duration.ofSeconds(properties.catchupWindowSeconds()))
                .setPauseOnFailure(false)
                .build())
            .setState(ScheduleState.newBuilder()
                .setPaused(!source.isEnabled())
                .setNote("Managed by chatchat-runtime-news for source " + source.getId())
                .build())
            .build();
    }

    private void upsert(String scheduleId, NewsSourceEntity source, Schedule desired) {
        try {
            scheduleClient.createSchedule(scheduleId, desired, ScheduleOptions.newBuilder()
                .setTriggerImmediately(source.isEnabled() && source.getLastCollectedAt() == null)
                .setMemo(new HashMap<>(Map.of("newsSourceId", source.getId())))
                .build());
            log.info("news_temporal_schedule_created scheduleId={} sourceId={} cron={}",
                scheduleId, source.getId(), source.getScheduleCron());
        } catch (ScheduleAlreadyRunningException exists) {
            scheduleClient.getHandle(scheduleId).update(ignored -> new ScheduleUpdate(desired));
            log.info("news_temporal_schedule_updated scheduleId={} sourceId={} cron={}",
                scheduleId, source.getId(), source.getScheduleCron());
        }
    }

    private void pauseInvalidSchedule(String scheduleId, NewsSourceEntity source, RuntimeException invalid) {
        try {
            scheduleClient.getHandle(scheduleId).pause("Invalid news source schedule: " + invalid.getMessage());
        } catch (RuntimeException ignored) {
            // The schedule may not exist yet. The invalid definition is still reported below.
        }
        log.warn("news_temporal_schedule_invalid scheduleId={} sourceId={} cron={} error={}",
            scheduleId, source.getId(), source.getScheduleCron(), invalid.getMessage());
    }

    private Set<String> managedScheduleIds() {
        Set<String> ids = new HashSet<>();
        try (var schedules = scheduleClient.listSchedules()) {
            schedules.map(description -> description.getScheduleId())
                .filter(id -> id.startsWith(SCHEDULE_PREFIX))
                .forEach(ids::add);
        }
        return ids;
    }

    private void removeStaleSchedules(Set<String> desiredIds, Set<String> remoteIds) {
        remoteIds.stream().filter(id -> !desiredIds.contains(id)).forEach(id -> {
            scheduleClient.getHandle(id).delete();
            appliedFingerprints.remove(id);
            log.info("news_temporal_schedule_deleted scheduleId={}", id);
        });
    }

    private String fingerprint(NewsSourceEntity source) {
        return source.getScheduleCron().trim() + "|"
            + schedulePolicy.zoneId(source.getConfigurationJson()).getId() + "|"
            + source.isEnabled() + "|" + properties.taskQueue() + "|"
            + properties.activityStartToCloseSeconds() + "|" + properties.activityMaximumAttempts()
            + "|" + properties.catchupWindowSeconds();
    }

    static String temporalCron(String springCron) {
        CronExpression.parse(springCron);
        String[] fields = springCron.trim().split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException("News schedule must be a six-field Spring cron expression");
        }
        if (!"0".equals(fields[0])) {
            throw new IllegalArgumentException("Temporal schedules use minute precision; cron seconds must be 0");
        }
        String[] temporalFields = java.util.Arrays.copyOfRange(fields, 1, fields.length);
        for (int i = 0; i < temporalFields.length; i++) {
            if ("?".equals(temporalFields[i])) temporalFields[i] = "*";
        }
        return String.join(" ", temporalFields);
    }

    static String scheduleId(Long sourceId) {
        return SCHEDULE_PREFIX + sourceId;
    }
}
