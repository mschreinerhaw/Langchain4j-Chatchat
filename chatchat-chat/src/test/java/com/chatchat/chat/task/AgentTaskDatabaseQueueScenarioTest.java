package com.chatchat.chat.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:agent_queue;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.show-sql=false"
})
@ContextConfiguration(classes = AgentTaskDatabaseQueueScenarioTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentTaskDatabaseQueueScenarioTest {

    @Autowired private AgentTaskLatestRepository taskRepository;
    @Autowired private TenantRuntimeQuotaRepository quotaRepository;
    @Autowired private AgentTaskQueueCoordinator coordinator;
    @Autowired private AgentTaskProperties properties;

    @BeforeEach
    void reset() {
        taskRepository.deleteAll();
        quotaRepository.deleteAll();
        properties.setDatabaseQueueClaimBatchSize(16);
        properties.setMaxConcurrentTasksPerTenant(4);
        properties.setWorkerVersion("1.0");
        properties.setWorkerCapabilities("dag-v2,conditional-edge-v1");
        properties.setRetryBaseDelayMs(100);
        properties.setRetryMaxDelayMs(500);
    }

    @Test
    void claimsEveryTaskAtMostOnceUnderConcurrentDatabasePressure() throws Exception {
        int tenants = 8;
        int totalTasks = 160;
        for (int tenant = 0; tenant < tenants; tenant++) {
            quotaRepository.save(quota("tenant-" + tenant, 0));
        }
        for (int index = 0; index < totalTasks; index++) {
            taskRepository.save(task("stress-" + index, "tenant-" + (index % tenants)));
        }

        Set<String> uniqueClaims = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateClaims = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        for (int worker = 0; worker < 8; worker++) {
            String workerId = "worker-" + worker;
            workers.submit(() -> {
                start.await();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (uniqueClaims.size() < totalTasks && System.nanoTime() < deadline) {
                    var claims = coordinator.claimAvailable(workerId);
                    if (claims.isEmpty()) {
                        Thread.onSpinWait();
                        continue;
                    }
                    for (var claim : claims) {
                        if (!uniqueClaims.add(claim.taskId())) {
                            duplicateClaims.incrementAndGet();
                        }
                        AgentTaskLatestEntity claimed = taskRepository.findById(claim.taskId()).orElseThrow();
                        claimed.setStatus("SUCCESS");
                        taskRepository.saveAndFlush(claimed);
                        coordinator.finish(claim);
                    }
                }
                return null;
            });
        }
        start.countDown();
        workers.shutdown();
        assertThat(workers.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
        assertThat(uniqueClaims).hasSize(totalTasks);
        assertThat(duplicateClaims).hasValue(0);
        assertThat(quotaRepository.findAll()).allMatch(quota -> quota.getActiveRuns() == 0);
        assertThat(taskRepository.findAll()).allMatch(task -> "SUCCESS".equals(task.getStatus()));
    }

    @Test
    void provisionsMissingTenantQuotaSafelyDuringConcurrentFirstClaim() throws Exception {
        taskRepository.saveAndFlush(task("first-claim", "new-tenant"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<java.util.List<AgentTaskQueueCoordinator.ClaimedTask>> first = workers.submit(() -> {
            start.await();
            return coordinator.claimAvailable("first-worker");
        });
        Future<java.util.List<AgentTaskQueueCoordinator.ClaimedTask>> second = workers.submit(() -> {
            start.await();
            return coordinator.claimAvailable("second-worker");
        });
        start.countDown();
        var combined = new java.util.ArrayList<AgentTaskQueueCoordinator.ClaimedTask>();
        combined.addAll(first.get(10, TimeUnit.SECONDS));
        combined.addAll(second.get(10, TimeUnit.SECONDS));
        workers.shutdownNow();

        assertThat(combined).hasSize(1);
        assertThat(quotaRepository.findById("new-tenant")).isPresent();
        complete(combined.get(0));
    }

    @Test
    void enforcesGlobalTenantCapacityAndWorkerVersionRouting() {
        properties.setMaxConcurrentTasksPerTenant(1);
        properties.setDatabaseQueueClaimBatchSize(8);
        quotaRepository.save(quota("tenant-capacity", 0));
        AgentTaskLatestEntity compatible = task("compatible", "tenant-capacity");
        AgentTaskLatestEntity next = task("next", "tenant-capacity");
        AgentTaskLatestEntity v2 = task("requires-v2", "tenant-capacity");
        v2.setRequiredWorkerVersion("2.0");
        taskRepository.save(compatible);
        taskRepository.save(next);
        taskRepository.save(v2);

        var first = coordinator.claimAvailable("worker-a");
        assertThat(first).hasSize(1);
        assertThat(coordinator.claimAvailable("worker-b")).isEmpty();
        complete(first.get(0));

        var second = coordinator.claimAvailable("worker-b");
        assertThat(second).hasSize(1);
        complete(second.get(0));
        assertThat(coordinator.claimAvailable("worker-a")).isEmpty();

        properties.setWorkerVersion("2.0");
        assertThat(coordinator.claimAvailable("worker-v2"))
            .singleElement().extracting(AgentTaskQueueCoordinator.ClaimedTask::taskId)
            .isEqualTo("requires-v2");
    }

    @Test
    void expiredLeaseRetriesWithBackoffThenMovesToDeadLetterAndReleasesQuota() {
        quotaRepository.save(quota("tenant-recovery", 1));
        AgentTaskLatestEntity task = task("lease-task", "tenant-recovery");
        task.setStatus("RUNNING");
        task.setClaimWorkerId("lost-worker");
        task.setClaimToken("claim-1");
        task.setLeaseExpiresAt(Instant.now().minusSeconds(2));
        task.setAttemptCount(1);
        task.setMaxAttempts(2);
        taskRepository.saveAndFlush(task);

        assertThat(coordinator.recoverExpiredClaims()).isEqualTo(1);
        AgentTaskLatestEntity retry = taskRepository.findById(task.getTaskId()).orElseThrow();
        assertThat(retry.getStatus()).isEqualTo("RETRY_WAIT");
        assertThat(retry.getAvailableAt()).isAfter(Instant.now());
        assertThat(retry.getClaimToken()).isNull();
        assertThat(quotaRepository.findById("tenant-recovery").orElseThrow().getActiveRuns()).isZero();

        retry.setStatus("RUNNING");
        retry.setClaimWorkerId("lost-worker-2");
        retry.setClaimToken("claim-2");
        retry.setLeaseExpiresAt(Instant.now().minusSeconds(2));
        retry.setAttemptCount(2);
        taskRepository.saveAndFlush(retry);
        TenantRuntimeQuotaEntity quota = quotaRepository.findById("tenant-recovery").orElseThrow();
        quota.setActiveRuns(1);
        quotaRepository.saveAndFlush(quota);

        assertThat(coordinator.recoverExpiredClaims()).isEqualTo(1);
        AgentTaskLatestEntity dead = taskRepository.findById(task.getTaskId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo("DLQ");
        assertThat(dead.getDeadLetterReason()).contains("lease expired");
        assertThat(quotaRepository.findById("tenant-recovery").orElseThrow().getActiveRuns()).isZero();
    }

    private void complete(AgentTaskQueueCoordinator.ClaimedTask claim) {
        AgentTaskLatestEntity task = taskRepository.findById(claim.taskId()).orElseThrow();
        task.setStatus("SUCCESS");
        taskRepository.saveAndFlush(task);
        coordinator.finish(claim);
    }

    private AgentTaskLatestEntity task(String id, String tenant) {
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId(id);
        task.setTenantId(tenant);
        task.setUserId("user");
        task.setSessionId("session-" + id);
        task.setStatus("PENDING");
        task.setAvailableAt(Instant.now().minusSeconds(1));
        task.setCreateTime(Instant.now());
        task.setUpdateTime(Instant.now());
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        return task;
    }

    private TenantRuntimeQuotaEntity quota(String tenant, int active) {
        TenantRuntimeQuotaEntity quota = new TenantRuntimeQuotaEntity();
        quota.setTenantId(tenant);
        quota.setActiveRuns(active);
        quota.setMaxConcurrentRuns(properties.getMaxConcurrentTasksPerTenant());
        return quota;
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = {
        AgentTaskLatestRepository.class, TenantRuntimeQuotaRepository.class
    })
    @EntityScan(basePackageClasses = {
        AgentTaskLatestEntity.class, TenantRuntimeQuotaEntity.class
    })
    @Import({AgentTaskQueueCoordinator.class, TenantRuntimeQuotaProvisioner.class, AgentTaskProperties.class})
    static class Config {
    }
}
