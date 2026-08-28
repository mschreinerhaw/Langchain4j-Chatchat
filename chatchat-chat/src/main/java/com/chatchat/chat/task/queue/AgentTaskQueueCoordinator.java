package com.chatchat.chat.task.queue;

import com.chatchat.chat.task.core.AgentTaskProperties;

import com.chatchat.chat.task.core.AgentTaskLatestRepository;

import com.chatchat.chat.task.core.AgentTaskLatestEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Database-backed durable queue, global tenant admission and lease recovery. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskQueueCoordinator {

    private final AgentTaskLatestRepository taskRepository;
    private final TenantRuntimeQuotaRepository quotaRepository;
    private final TenantRuntimeQuotaProvisioner quotaProvisioner;
    private final AgentTaskProperties properties;

    @Transactional
    public List<ClaimedTask> claimAvailable(String workerId) {
        Instant now = Instant.now();
        int batchSize = Math.max(1, properties.getDatabaseQueueClaimBatchSize());
        List<AgentTaskLatestEntity> candidates = new ArrayList<>(taskRepository.findDispatchCandidates(
            now, normalized(properties.getWorkerVersion(), "1.0"), PageRequest.of(0, batchSize * 8)));
        candidates.sort(Comparator
            .comparing((AgentTaskLatestEntity task) -> quotaRepository.findById(task.getTenantId())
                .map(TenantRuntimeQuotaEntity::getLastDispatchAt).orElse(Instant.EPOCH))
            .thenComparing(AgentTaskLatestEntity::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AgentTaskLatestEntity::getCreateTime));
        List<ClaimedTask> claimed = new ArrayList<>();
        Set<String> visitedTenants = new HashSet<>();
        for (AgentTaskLatestEntity candidate : candidates) {
            if (claimed.size() >= batchSize || !capabilitiesMatch(candidate)) {
                continue;
            }
            // One claim per tenant per scan provides database-wide round-robin fairness.
            if (!visitedTenants.add(candidate.getTenantId())) {
                continue;
            }
            TenantRuntimeQuotaEntity quota = lockedQuota(candidate.getTenantId());
            int limit = Math.max(1, properties.getMaxConcurrentTasksPerTenant());
            quota.setMaxConcurrentRuns(limit);
            int activeClaims = reconcileQuota(quota, now);
            if (activeClaims >= limit) {
                continue;
            }
            String token = UUID.randomUUID().toString();
            Instant expiresAt = now.plusMillis(Math.max(1_000L, properties.getWorkerLeaseMs()));
            if (taskRepository.claimTask(candidate.getTaskId(), workerId, token, now, expiresAt) != 1) {
                continue;
            }
            quota.setActiveRuns(activeClaims + 1);
            quota.setLastDispatchAt(now);
            quotaRepository.save(quota);
            claimed.add(new ClaimedTask(candidate.getTaskId(), candidate.getTenantId(), token, workerId));
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public boolean heartbeat(ClaimedTask claim) {
        Instant now = Instant.now();
        return taskRepository.heartbeatClaim(claim.taskId(), claim.claimToken(), now,
            now.plusMillis(Math.max(1_000L, properties.getWorkerLeaseMs()))) == 1;
    }

    @Transactional
    public void finish(ClaimedTask claim) {
        AgentTaskLatestEntity task = taskRepository.findByTaskIdForUpdate(claim.taskId()).orElse(null);
        if (task == null || !claim.claimToken().equals(task.getClaimToken())) {
            return;
        }
        if (isRetryableClaimStatus(task.getStatus())) {
            retryOrDeadLetter(task, "execution ended without a terminal result: "
                + normalized(task.getErrorMessage(), task.getStatus()));
        } else {
            clearClaim(task);
        }
        taskRepository.saveAndFlush(task);
        reconcileQuota(lockedQuota(task.getTenantId()), Instant.now());
    }

    @Transactional
    public void requeueRejectedClaim(ClaimedTask claim, String reason) {
        AgentTaskLatestEntity task = taskRepository.findByTaskIdForUpdate(claim.taskId()).orElse(null);
        if (task == null || !claim.claimToken().equals(task.getClaimToken())) {
            return;
        }
        clearClaim(task);
        task.setStatus("RETRY_WAIT");
        task.setAvailableAt(Instant.now().plusMillis(Math.max(100L, properties.getDatabaseQueuePollMs())));
        task.setErrorMessage(reason);
        taskRepository.saveAndFlush(task);
        reconcileQuota(lockedQuota(task.getTenantId()), Instant.now());
    }

    @Transactional
    public int recoverExpiredClaims() {
        Instant now = Instant.now();
        List<AgentTaskLatestEntity> expired = taskRepository.findExpiredClaims(
            now, PageRequest.of(0, Math.max(1, properties.getRecoveryBatchSize())));
        int recovered = 0;
        for (AgentTaskLatestEntity candidate : expired) {
            AgentTaskLatestEntity task = taskRepository.findByTaskIdForUpdate(candidate.getTaskId()).orElse(null);
            if (task == null || task.getClaimToken() == null || task.getLeaseExpiresAt() == null
                || !task.getLeaseExpiresAt().isBefore(now)) {
                continue;
            }
            if (isRetryableClaimStatus(task.getStatus())) {
                retryOrDeadLetter(task, "worker lease expired: "
                    + normalized(task.getClaimWorkerId(), "unknown worker"));
            } else {
                clearClaim(task);
            }
            taskRepository.saveAndFlush(task);
            reconcileQuota(lockedQuota(task.getTenantId()), now);
            recovered++;
        }
        return recovered;
    }

    /** Requeues active snapshots that have no lease after a process stopped between state updates. */
    @Transactional
    public int recoverOrphanedActiveTasks() {
        Instant now = Instant.now();
        List<AgentTaskLatestEntity> orphans = taskRepository.findOrphanedActiveTasks(
            PageRequest.of(0, Math.max(1, properties.getRecoveryBatchSize())));
        int recovered = 0;
        for (AgentTaskLatestEntity candidate : orphans) {
            AgentTaskLatestEntity task = taskRepository.findByTaskIdForUpdate(candidate.getTaskId()).orElse(null);
            if (task == null || task.getClaimToken() != null || !isActiveExecutionStatus(task.getStatus())) {
                continue;
            }
            clearClaim(task);
            task.setStatus("PENDING");
            task.setAvailableAt(now);
            task.setErrorMessage("Recovered orphaned Agent execution after worker restart");
            taskRepository.saveAndFlush(task);
            recovered++;
        }
        return recovered;
    }

    /** Repairs denormalized quota counters from authoritative, non-expired database claims. */
    @Transactional
    public int reconcileQuotaCounters() {
        Instant now = Instant.now();
        List<String> tenantIds = quotaRepository.findDriftedTenantIds(
            now, PageRequest.of(0, Math.max(1, properties.getRecoveryBatchSize())));
        int corrected = 0;
        for (String tenantId : tenantIds) {
            TenantRuntimeQuotaEntity quota = lockedQuota(tenantId);
            int before = safe(quota.getActiveRuns());
            int after = reconcileQuota(quota, now);
            if (before != after) {
                corrected++;
                log.warn("Reconciled stale Agent runtime quota. tenantId={} activeRunsBefore={} activeRunsAfter={}",
                    tenantId, before, after);
            }
        }
        return corrected;
    }

    private TenantRuntimeQuotaEntity lockedQuota(String tenantId) {
        quotaProvisioner.ensureExists(tenantId);
        return quotaRepository.findForUpdate(tenantId)
            .orElseThrow(() -> new IllegalStateException("Unable to provision tenant runtime quota: " + tenantId));
    }

    private int reconcileQuota(TenantRuntimeQuotaEntity quota, Instant now) {
        int activeClaims = Math.toIntExact(taskRepository.countValidClaims(quota.getTenantId(), now));
        if (safe(quota.getActiveRuns()) != activeClaims) {
            quota.setActiveRuns(activeClaims);
            quotaRepository.save(quota);
        }
        return activeClaims;
    }

    private void retryOrDeadLetter(AgentTaskLatestEntity task, String reason) {
        clearClaim(task);
        int attempts = safe(task.getAttemptCount());
        int maxAttempts = Math.max(1, task.getMaxAttempts() == null
            ? properties.getTaskMaxAttempts() : task.getMaxAttempts());
        if (attempts >= maxAttempts) {
            task.setStatus("DLQ");
            task.setDeadLetterReason(reason);
            task.setErrorMessage(reason);
            return;
        }
        long base = Math.max(100L, properties.getRetryBaseDelayMs());
        long ceiling = Math.max(base, properties.getRetryMaxDelayMs());
        long exponential = base * (1L << Math.min(20, Math.max(0, attempts - 1)));
        long jitter = Math.floorMod(task.getTaskId().hashCode(), Math.max(1L, base));
        task.setStatus("RETRY_WAIT");
        task.setAvailableAt(Instant.now().plusMillis(Math.min(ceiling, exponential) + jitter));
        task.setErrorMessage(reason);
    }

    private void clearClaim(AgentTaskLatestEntity task) {
        task.setClaimWorkerId(null);
        task.setClaimToken(null);
        task.setHeartbeatAt(null);
        task.setLeaseExpiresAt(null);
    }

    private boolean isRetryableClaimStatus(String status) {
        return status != null && Set.of(
            "CLAIMED", "RUNNING", "WAIT_MODEL", "WAIT_TOOL"
        ).contains(status.toUpperCase());
    }

    private boolean isActiveExecutionStatus(String status) {
        return status != null && Set.of(
            "CLAIMED", "RUNNING", "WAIT_MODEL", "WAIT_TOOL"
        ).contains(status.toUpperCase());
    }

    private boolean capabilitiesMatch(AgentTaskLatestEntity task) {
        if (task.getRequiredWorkerCapabilities() == null || task.getRequiredWorkerCapabilities().isBlank()) {
            return true;
        }
        Set<String> available = new HashSet<>(Arrays.stream(
            normalized(properties.getWorkerCapabilities(), "").split(","))
            .map(String::trim).filter(value -> !value.isBlank()).toList());
        return Arrays.stream(task.getRequiredWorkerCapabilities().split(","))
            .map(String::trim).filter(value -> !value.isBlank()).allMatch(available::contains);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record ClaimedTask(String taskId, String tenantId, String claimToken, String workerId) {
    }
}
