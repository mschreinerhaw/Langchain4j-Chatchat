package com.chatchat.chat.task.queue;

import com.chatchat.chat.task.core.AgentTaskService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskTimeoutScheduler {

    private final AgentTaskService taskService;

    @Scheduled(fixedDelayString = "${chatchat.agent.task.confirmation-timeout-scan-ms:30000}")
    public void cancelExpiredConfirmations() {
        try {
            int expired = taskService.cancelExpiredConfirmations();
            if (expired > 0) {
                log.info("Cancelled {} expired Agent confirmation tasks", expired);
            }
        } catch (Exception ex) {
            log.warn("Failed to cancel expired Agent confirmation tasks: {}", ex.getMessage());
        }
    }
}
