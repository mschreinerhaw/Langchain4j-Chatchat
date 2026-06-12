package com.chatchat.chat.task;

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
}
