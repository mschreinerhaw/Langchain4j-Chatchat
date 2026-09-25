package com.chatchat.integration.agent;

import com.chatchat.agents.runtime.federation.AgentHealthStateStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cross-instance SLA/circuit state, updated under a row lock. */
@Component
public class JpaAgentHealthStateStore implements AgentHealthStateStore {
    private final AgentHealthRepository repository;
    private final TransactionTemplate transactions;

    public JpaAgentHealthStateStore(AgentHealthRepository repository,
                                    PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override public State read(String agentId) {
        return repository.findById(agentId).map(AgentHealthEntity::state).orElse(State.empty());
    }

    @Override public State record(String agentId, boolean failed, long latencyMs, long nowEpochMs) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactions.execute(status -> {
                    AgentHealthEntity entity = repository.findForUpdate(agentId)
                        .orElseGet(() -> new AgentHealthEntity(agentId));
                    State next = entity.state().next(failed, latencyMs, nowEpochMs);
                    entity.apply(next);
                    repository.saveAndFlush(entity);
                    return next;
                });
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException conflict) {
                if (attempt == 2) throw conflict;
            }
        }
        throw new IllegalStateException("Agent health update failed");
    }
}
