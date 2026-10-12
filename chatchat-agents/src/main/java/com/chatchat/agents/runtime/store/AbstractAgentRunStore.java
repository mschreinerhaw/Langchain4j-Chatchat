package com.chatchat.agents.runtime.store;

import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.event.AgentRunEventPublisher;
import com.chatchat.agents.runtime.event.NoopAgentRunEventPublisher;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared event-delivery base for agent-run persistence implementations.
 *
 * <p>Persistence remains the concrete store's responsibility. External delivery is isolated and
 * cannot invalidate an already accepted runtime state transition.</p>
 */
@Slf4j
public abstract class AbstractAgentRunStore implements AgentRunStore {

    private final AgentRunEventPublisher eventPublisher;

    protected AbstractAgentRunStore(AgentRunEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher == null ? new NoopAgentRunEventPublisher() : eventPublisher;
    }

    protected final void publishEvent(AgentRunEvent event) {
        com.chatchat.common.runtime.event.RuntimeEventLog.write(event);
        try {
            eventPublisher.publish(event);
        } catch (RuntimeException error) {
            log.warn("Agent run event publisher failed. runId={} eventType={} errorType={} error={}",
                event == null ? null : event.runId(), event == null ? null : event.type(),
                error.getClass().getName(), error.getMessage(), error);
        }
    }

    protected final void logPlanSnapshotSaved(
        com.chatchat.agents.runtime.plan.persistence.InterpretationPlanRecord record,
        com.chatchat.agents.runtime.plan.InterpretationPlan plan) {
        com.chatchat.common.runtime.event.RuntimeEventLog.writeFact(record.taskId(), "PLAN_SNAPSHOT_SAVED",
            java.util.Map.of("taskId", record.taskId(), "tenantId", record.tenantId(), "planId", record.planId(),
                "planVersion", record.version(), "status", record.status(), "steps", plan == null
                    ? java.util.List.of() : plan.steps().stream().filter(java.util.Objects::nonNull).map(step -> {
                        java.util.Map<String,Object> shape = new java.util.LinkedHashMap<>();
                        shape.put("stepId", step.id()); shape.put("actionType", step.actionType());
                        shape.put("toolName", step.toolName()); shape.put("dependsOn", step.dependsOn());
                        return shape;
                    }).toList()));
    }
}
