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
        try {
            eventPublisher.publish(event);
        } catch (RuntimeException error) {
            log.warn("Agent run event publisher failed. runId={} eventType={} error={}",
                event == null ? null : event.runId(), event == null ? null : event.type(), error.getMessage());
        }
    }
}
