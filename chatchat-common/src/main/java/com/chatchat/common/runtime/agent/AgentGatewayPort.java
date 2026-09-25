package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

public interface AgentGatewayPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.agent_gateway.v1";
    AgentExecutionOutcome invoke(AgentDescriptor agent, AgentExecutionRequest request);
    default AgentExecutionOutcome cancel(AgentDescriptor agent, String executionId) {
        throw new UnsupportedOperationException("agent cancellation is not supported");
    }
    default String protocolVersion() { return PROTOCOL_VERSION; }
}
