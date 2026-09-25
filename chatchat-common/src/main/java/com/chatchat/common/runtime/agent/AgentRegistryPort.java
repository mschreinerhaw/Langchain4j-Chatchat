package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.capability.CapabilityId;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;
import java.util.Optional;

public interface AgentRegistryPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.agent_registry.v1";
    Optional<AgentDescriptor> find(String agentId);
    List<AgentDescriptor> findByCapability(CapabilityId capability);
    List<AgentDescriptor> list();
    void register(AgentDescriptor descriptor);
    void remove(String agentId);

    default String protocolVersion() { return PROTOCOL_VERSION; }
}
