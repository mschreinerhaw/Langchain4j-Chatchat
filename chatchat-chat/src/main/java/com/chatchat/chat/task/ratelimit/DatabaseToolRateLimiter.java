package com.chatchat.chat.task.ratelimit;

import com.chatchat.agents.runtime.tool.DistributedToolRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Relational fixed-window limiter shared by every application instance. */
@Service
@RequiredArgsConstructor
public class DatabaseToolRateLimiter implements DistributedToolRateLimiter {

    private final ToolRateBucketRepository repository;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(fixedDelayString = "${chatchat.tool-runtime.database-rate-bucket-cleanup-ms:3600000}")
    public void cleanupExpiredBuckets() {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> repository.deleteExpired(Instant.now().minus(1, ChronoUnit.MINUTES)));
    }

    @Override
    public boolean tryAcquire(String tenantId, String toolName, String actorId,
                              int minuteLimit, int secondLimit, Instant now) {
        List<BucketSpec> specs = specs(tenantId, toolName, actorId, minuteLimit, secondLimit,
            now == null ? Instant.now() : now);
        if (specs.isEmpty()) {
            return true;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Boolean accepted = new TransactionTemplate(transactionManager).execute(status -> acquire(specs));
                return Boolean.TRUE.equals(accepted);
            } catch (DataIntegrityViolationException collision) {
                if (attempt == 3) {
                    throw collision;
                }
            }
        }
        return false;
    }

    private boolean acquire(List<BucketSpec> specs) {
        List<ToolRateBucketEntity> buckets = new ArrayList<>();
        for (BucketSpec spec : specs.stream().sorted(Comparator.comparing(BucketSpec::id)).toList()) {
            ToolRateBucketEntity bucket = repository.findForUpdate(spec.id()).orElseGet(() -> create(spec));
            if (bucket.getUsedTokens() != null && bucket.getUsedTokens() >= spec.limit()) {
                return false;
            }
            buckets.add(bucket);
        }
        for (ToolRateBucketEntity bucket : buckets) {
            bucket.setUsedTokens((bucket.getUsedTokens() == null ? 0 : bucket.getUsedTokens()) + 1);
            repository.save(bucket);
        }
        return true;
    }

    private ToolRateBucketEntity create(BucketSpec spec) {
        ToolRateBucketEntity bucket = new ToolRateBucketEntity();
        bucket.setBucketId(spec.id());
        bucket.setTenantId(spec.tenantId());
        bucket.setToolName(spec.toolName());
        bucket.setWindowType(spec.type());
        bucket.setWindowStart(spec.start());
        bucket.setExpiresAt(spec.expiresAt());
        bucket.setTokenLimit(spec.limit());
        bucket.setUsedTokens(0);
        return repository.saveAndFlush(bucket);
    }

    private List<BucketSpec> specs(String tenant, String tool, String actor,
                                   int minuteLimit, int secondLimit, Instant now) {
        List<BucketSpec> specs = new ArrayList<>();
        if (minuteLimit > 0) {
            Instant start = now.truncatedTo(ChronoUnit.MINUTES);
            specs.add(new BucketSpec(tenant + "::" + tool + "::" + actor + "::m::" + start.toEpochMilli(),
                tenant, tool, "MINUTE", start, start.plus(1, ChronoUnit.MINUTES), minuteLimit));
        }
        if (secondLimit > 0) {
            Instant start = now.truncatedTo(ChronoUnit.SECONDS);
            specs.add(new BucketSpec(tenant + "::" + tool + "::s::" + start.toEpochMilli(),
                tenant, tool, "SECOND", start, start.plus(1, ChronoUnit.SECONDS), secondLimit));
        }
        return specs;
    }

    private record BucketSpec(String id, String tenantId, String toolName, String type,
                              Instant start, Instant expiresAt, int limit) {
    }
}
