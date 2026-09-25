package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

public interface AgentComputeRuntimePort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.agent_compute.v1";
    AgentExecutionOutcome execute(AgentExecutionRequest request);
    default String protocolVersion() { return PROTOCOL_VERSION; }
}
