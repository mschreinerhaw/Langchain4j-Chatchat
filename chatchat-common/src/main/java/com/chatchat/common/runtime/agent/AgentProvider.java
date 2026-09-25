package com.chatchat.common.runtime.agent;

/** Local provider SPI. Remote transport implementations belong behind AgentGatewayPort. */
public interface AgentProvider {
    AgentDescriptor descriptor();
    default boolean available(AgentExecutionRequest request) { return descriptor().enabled(); }
    AgentExecutionOutcome execute(AgentExecutionRequest request);
}
