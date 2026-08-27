package com.chatchat.chat.task.ratelimit;

import com.chatchat.chat.task.ratelimit.ToolRateBucketRepository;

import com.chatchat.chat.task.ratelimit.ToolRateBucketEntity;

import com.chatchat.chat.task.ratelimit.DatabaseToolRateLimiter;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:tool_quota;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.show-sql=false"
})
@ContextConfiguration(classes = DatabaseToolRateLimiterScenarioTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DatabaseToolRateLimiterScenarioTest {

    @Autowired private DatabaseToolRateLimiter limiter;
    @Autowired private ToolRateBucketRepository repository;

    @Test
    void sharesToolQpsAcrossConcurrentApplicationWorkersAndIsolatesTenants() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        seedSecondBucket("tenant-a", "market.search", now, 5);
        seedSecondBucket("tenant-b", "market.search", now, 5);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> tenantA = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            tenantA.add(workers.submit(() -> {
                start.await();
                return limiter.tryAcquire("tenant-a", "market.search", "actor", 0, 5, now);
            }));
        }
        Future<Boolean> tenantB = workers.submit(() -> {
            start.await();
            return limiter.tryAcquire("tenant-b", "market.search", "actor", 0, 5, now);
        });
        start.countDown();

        int accepted = 0;
        for (Future<Boolean> result : tenantA) {
            if (result.get(15, TimeUnit.SECONDS)) {
                accepted++;
            }
        }
        assertThat(tenantB.get(15, TimeUnit.SECONDS)).isTrue();
        workers.shutdown();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(accepted).isEqualTo(5);
        assertThat(repository.findById(bucketId("tenant-a", "market.search", now)).orElseThrow().getUsedTokens())
            .isEqualTo(5);
        assertThat(repository.findById(bucketId("tenant-b", "market.search", now)).orElseThrow().getUsedTokens())
            .isEqualTo(1);
    }

    private void seedSecondBucket(String tenant, String tool, Instant now, int limit) {
        ToolRateBucketEntity bucket = new ToolRateBucketEntity();
        bucket.setBucketId(bucketId(tenant, tool, now));
        bucket.setTenantId(tenant);
        bucket.setToolName(tool);
        bucket.setWindowType("SECOND");
        bucket.setWindowStart(now);
        bucket.setExpiresAt(now.plusSeconds(1));
        bucket.setTokenLimit(limit);
        bucket.setUsedTokens(0);
        repository.saveAndFlush(bucket);
    }

    private String bucketId(String tenant, String tool, Instant now) {
        return tenant + "::" + tool + "::s::" + now.toEpochMilli();
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = ToolRateBucketRepository.class)
    @EntityScan(basePackageClasses = ToolRateBucketEntity.class)
    @Import(DatabaseToolRateLimiter.class)
    static class Config {
    }
}
