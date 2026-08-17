package com.chatchat.chat.task;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates tenant quota rows in an isolated transaction so concurrent first use cannot poison a claim transaction. */
@Service
@RequiredArgsConstructor
public class TenantRuntimeQuotaProvisioner {

    private final TenantRuntimeQuotaRepository repository;
    private final PlatformTransactionManager transactionManager;
    private final AgentTaskProperties properties;

    public void ensureExists(String tenantId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            template.executeWithoutResult(status -> {
                if (repository.existsById(tenantId)) {
                    return;
                }
                TenantRuntimeQuotaEntity quota = new TenantRuntimeQuotaEntity();
                quota.setTenantId(tenantId);
                quota.setActiveRuns(0);
                quota.setMaxConcurrentRuns(Math.max(1, properties.getMaxConcurrentTasksPerTenant()));
                repository.saveAndFlush(quota);
            });
        } catch (DataIntegrityViolationException concurrentCreate) {
            // Another node committed the same tenant row; the caller will lock that row next.
        }
    }
}
