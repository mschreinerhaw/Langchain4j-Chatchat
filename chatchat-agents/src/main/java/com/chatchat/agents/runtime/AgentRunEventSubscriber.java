package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.event.RuntimeEventSubscriber;

/** Typed subscriber port for the agent-run event stream. */
@FunctionalInterface
public interface AgentRunEventSubscriber extends RuntimeEventSubscriber<AgentRunEvent> {
}
