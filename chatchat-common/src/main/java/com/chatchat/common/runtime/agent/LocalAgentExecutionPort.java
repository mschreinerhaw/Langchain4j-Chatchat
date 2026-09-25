package com.chatchat.common.runtime.agent;

/** Compatibility boundary for locally published agents that are selected from a dynamic catalog. */
public interface LocalAgentExecutionPort {
    boolean supports(String agentId);
    AgentExecutionOutcome execute(AgentDescriptor descriptor, AgentExecutionRequest request);
}
