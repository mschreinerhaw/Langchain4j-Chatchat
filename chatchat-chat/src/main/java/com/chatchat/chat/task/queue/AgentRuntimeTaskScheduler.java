package com.chatchat.chat.task.queue;

import com.chatchat.chat.task.schedule.AgentScheduledTaskService;

import com.chatchat.chat.task.core.AgentTaskService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntimeTaskScheduler {

    private final AgentScheduledTaskService scheduledTaskService;
    private final AgentTaskService taskService;
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${chatchat.agent.task.scheduler-scan-ms:30000}")
    public void scanDueTasks() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            int changed = scheduledTaskService.scanDueTasks();
            if (changed > 0) {
                log.info("Processed {} Agent Runtime scheduled task records", changed);
            }
        } catch (Exception ex) {
            log.warn("Failed to scan Agent Runtime scheduled tasks: {}", ex.getMessage());
        } finally {
            scanning.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${chatchat.agent.task.database-queue-poll-ms:250}")
    public void dispatchDatabaseQueue() {
        try {
            taskService.dispatchPersistentTasks();
        } catch (Exception ex) {
            log.warn("Failed to dispatch database Agent queue: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${chatchat.agent.task.database-lease-recovery-ms:1000}")
    public void recoverExpiredDatabaseClaims() {
        try {
            int recovered = taskService.recoverExpiredDatabaseClaims();
            if (recovered > 0) {
                log.warn("Recovered {} expired database Agent claims", recovered);
            }
        } catch (Exception ex) {
            log.warn("Failed to recover expired database Agent claims: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${chatchat.agent.task.database-quota-reconcile-ms:30000}")
    public void reconcileDatabaseQueueQuotas() {
        try {
            int corrected = taskService.reconcileDatabaseQueueQuotas();
            if (corrected > 0) {
                log.warn("Reconciled {} stale database Agent quota records", corrected);
            }
        } catch (Exception ex) {
            log.warn("Failed to reconcile database Agent quotas: {}", ex.getMessage());
        }
    }
}
