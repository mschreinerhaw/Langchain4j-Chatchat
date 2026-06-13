package com.chatchat.agents.runtime;

import org.springframework.stereotype.Component;

@Component
public class NoopAgentRunEventPublisher implements AgentRunEventPublisher {

    @Override
    public void publish(AgentRunEvent event) {
        // Default extension point: deployments can replace this with SSE, MQ, or metrics integration.
    }
}
