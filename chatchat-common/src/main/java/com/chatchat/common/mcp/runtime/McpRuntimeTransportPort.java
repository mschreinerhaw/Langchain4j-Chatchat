package com.chatchat.common.mcp.runtime;

import com.chatchat.common.kernel.KernelHealth;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/**
 * Southbound API-to-MCP communication boundary. Implementations may be local or gRPC,
 * while callers remain coupled only to the canonical MCP Runtime OS contracts.
 */
public interface McpRuntimeTransportPort extends RuntimeProtocolPort,
    McpServiceDirectory, McpRuntimeContractService {

    String PROTOCOL_VERSION = "runtime_os.mcp.transport.v1";

    KernelHealth health();
}
