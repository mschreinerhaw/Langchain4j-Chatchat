package com.chatchat.agents.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/** Default in-process event delivery implementation; replaceable by MQ or streaming adapters. */
@Slf4j
@Component
@ConditionalOnMissingBean(AgentRunEventPublisher.class)
public class DefaultAgentRunEventBus implements AgentRunEventPublisher {

    private final List<AgentRunEventSubscriber> subscribers;

    public DefaultAgentRunEventBus(List<AgentRunEventSubscriber> subscribers) {
        this.subscribers = subscribers == null ? List.of() : List.copyOf(subscribers);
    }

    @Override
    public void publish(AgentRunEvent event) {
        if (event == null) {
            return;
        }
        for (AgentRunEventSubscriber subscriber : subscribers) {
            try {
                subscriber.onEvent(event);
            } catch (RuntimeException error) {
                log.warn("Runtime event subscriber failed. subscriber={} streamId={} eventType={} error={}",
                    subscriber.getClass().getName(), event.streamId(), event.eventType(), error.getMessage());
            }
        }
    }
}
