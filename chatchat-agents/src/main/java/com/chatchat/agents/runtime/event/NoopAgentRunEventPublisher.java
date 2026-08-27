package com.chatchat.agents.runtime.event;

public class NoopAgentRunEventPublisher implements AgentRunEventPublisher {

    @Override
    public void publish(AgentRunEvent event) {
        // Default extension point: deployments can replace this with SSE, MQ, or metrics integration.
    }
}
