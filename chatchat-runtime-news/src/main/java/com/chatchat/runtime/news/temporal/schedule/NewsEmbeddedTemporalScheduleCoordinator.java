package com.chatchat.runtime.news.temporal.schedule;

import com.chatchat.runtime.news.collector.schedule.NewsCollectionSchedulePolicy;
import com.chatchat.runtime.news.source.persistence.NewsSourceEntity;
import com.chatchat.runtime.news.source.persistence.NewsSourceRepository;
import com.chatchat.runtime.news.temporal.config.NewsTemporalProperties;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowCommand;
import com.chatchat.runtime.news.temporal.workflow.NewsCollectionWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.RetryOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local JVM scheduler used with Temporal's in-process test service, which intentionally does not
 * implement the server-side Schedule API. Workflow and Activity execution still runs through
 * Temporal; only the cron trigger is owned by Spring in embedded mode.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${chatchat.runtime.news.temporal.enabled:true}' == 'true' && "
    + "'${chatchat.runtime.news.temporal.server-mode:embedded}' == 'embedded'")
public class NewsEmbeddedTemporalScheduleCoordinator {
    private static final String WORKFLOW_PREFIX = "chatchat-news-collection-";

    private final NewsSourceRepository repository;
    private final NewsCollectionSchedulePolicy schedulePolicy;
    private final WorkflowClient workflowClient;
    private final NewsTemporalProperties properties;
    private final Map<Long, LocalScheduleState> schedules = new ConcurrentHashMap<>();

    public NewsEmbeddedTemporalScheduleCoordinator(NewsSourceRepository repository,
                                                    NewsCollectionSchedulePolicy schedulePolicy,
                                                    WorkflowClient workflowClient,
                                                    NewsTemporalProperties properties) {
        this.repository = repository;
        this.schedulePolicy = schedulePolicy;
        this.workflowClient = workflowClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${chatchat.runtime.news.temporal.reconcile-delay-millis:30000}")
    public void reconcile() {
        Instant now = Instant.now();
        Set<Long> activeIds = new HashSet<>();
        for (NewsSourceEntity source : repository.findAll()) {
            if (source.getId() == null || !source.isEnabled() || source.getScheduleCron() == null
                || source.getScheduleCron().isBlank()) {
                continue;
            }
            activeIds.add(source.getId());
            try {
                reconcile(source, now);
            } catch (RuntimeException invalid) {
                schedules.remove(source.getId());
                log.warn("news_embedded_schedule_invalid sourceId={} cron={} error={}",
                    source.getId(), source.getScheduleCron(), invalid.getMessage());
            }
        }
        schedules.keySet().removeIf(id -> !activeIds.contains(id));
    }

    private void reconcile(NewsSourceEntity source, Instant now) {
        var zone = schedulePolicy.zoneId(source.getConfigurationJson());
        var cron = CronExpression.parse(source.getScheduleCron().trim());
        String fingerprint = source.getScheduleCron().trim() + "|" + zone.getId();
        LocalScheduleState state = schedules.get(source.getId());
        if (state == null || !state.fingerprint().equals(fingerprint)) {
            Instant base = source.getLastCollectedAt();
            Instant next = base == null ? now : next(cron, base.atZone(zone));
            state = new LocalScheduleState(fingerprint, next);
            schedules.put(source.getId(), state);
        }
        if (state.nextFireTime() != null && !state.nextFireTime().isAfter(now)) {
            startWorkflow(source);
            schedules.put(source.getId(), new LocalScheduleState(fingerprint,
                next(cron, now.atZone(zone))));
        }
    }

    private void startWorkflow(NewsSourceEntity source) {
        var workflow = workflowClient.newWorkflowStub(NewsCollectionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(WORKFLOW_PREFIX + source.getId())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .setTaskQueue(properties.taskQueue())
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .setMemo(Map.of("newsSourceId", source.getId(),
                    "newsSourceCode", source.getSourceCode() == null ? "" : source.getSourceCode()))
                .build());
        var command = new NewsCollectionWorkflowCommand(source.getId(),
            properties.activityStartToCloseSeconds(), properties.activityMaximumAttempts());
        try {
            WorkflowClient.start(workflow::collect, command);
            log.info("news_embedded_workflow_started sourceId={} cron={}",
                source.getId(), source.getScheduleCron());
        } catch (WorkflowExecutionAlreadyStarted overlap) {
            log.info("news_embedded_workflow_overlap_skipped sourceId={}", source.getId());
        }
    }

    private Instant next(CronExpression cron, ZonedDateTime base) {
        ZonedDateTime value = cron.next(base);
        return value == null ? null : value.toInstant();
    }

    private record LocalScheduleState(String fingerprint, Instant nextFireTime) {}
}
