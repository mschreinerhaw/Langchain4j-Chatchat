package com.chatchat.chat.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Immediately synchronizes terminal Agent task events to their scheduler records. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScheduledTaskTerminalEventListener {

    private static final Set<String> TERMINAL_STATUSES = Set.of(
        "SUCCESS", "FAILED", "CANCELLED", "REJECTED", "TIMEOUT_CANCELLED", "KILLED"
    );

    private final AgentScheduledTaskService scheduledTaskService;

    @EventListener
    public void onAgentTaskTerminalEvent(AgentEvent event) {
        if (event == null || event.getTaskId() == null || event.getTaskId().isBlank()
            || !TERMINAL_STATUSES.contains(normalizeStatus(event.getStatus()))) {
            return;
        }
        try {
            scheduledTaskService.reconcileTerminalTask(event.getTaskId());
        } catch (Exception ex) {
            // Never turn a successful Kill into an API error. The periodic scanner remains the recovery path.
            log.warn("Failed to reconcile terminal Agent task immediately taskId={}: {}",
                event.getTaskId(), ex.getMessage());
        }
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }
}
