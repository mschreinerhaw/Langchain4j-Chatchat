package com.chatchat.agents.runtime.store;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically removes expired terminal Agent runs and their persisted evidence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "chatchat.agent-runtime",
    name = "cleanup-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AgentRunRetentionScheduler {

    private final AgentRunStore runStore;
    private final AgentRuntimeProperties properties;

    @Scheduled(
        fixedDelayString = "${chatchat.agent-runtime.cleanup-interval-ms:3600000}",
        initialDelayString = "${chatchat.agent-runtime.cleanup-initial-delay-ms:60000}"
    )
    public void cleanup() {
        try {
            int removed = runStore.cleanupExpiredRuns();
            if (removed > 0) {
                log.info("Agent run retention cleanup completed removedRuns={} retentionMs={}",
                    removed, properties.terminalRunTtlMs());
            }
        } catch (RuntimeException ex) {
            log.warn("Agent run retention cleanup failed: type={} detail={}",
                ex.getClass().getSimpleName(),
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.debug("Agent run retention cleanup stack trace", ex);
        }
    }
}
