package com.chatchat.common.mcp.proxy;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.Map;

/** Transport-neutral control port for administratively approved MCP proxy sessions. */
public interface McpTransportProxyPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.transport_proxy.v1";

    Map<String, Object> forward(String serviceId, Map<String, Object> request);

    void closeSession(String serviceId);
}
