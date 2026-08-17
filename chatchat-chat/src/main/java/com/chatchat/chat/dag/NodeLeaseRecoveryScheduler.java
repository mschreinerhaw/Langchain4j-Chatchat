package com.chatchat.chat.dag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Periodically fences attempts whose worker stopped heartbeating. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeLeaseRecoveryScheduler {

    private static final String RECOVERY_WORKER_ID = "recovery-" + UUID.randomUUID();
    private final DatabaseNodeAttemptStore attemptStore;

    @Scheduled(fixedDelayString = "${chatchat.agent-runtime.lease-recovery-scan-ms:5000}")
    public void reclaimExpiredWorkers() {
        var reclaimed = attemptStore.reclaimExpiredLeases(RECOVERY_WORKER_ID, Instant.now(), 100);
        if (!reclaimed.isEmpty()) {
            log.warn("Reclaimed {} DAG node attempts after worker lease expiry: {}", reclaimed.size(),
                reclaimed.stream().map(value -> value.attemptId()).toList());
        }
    }
}
