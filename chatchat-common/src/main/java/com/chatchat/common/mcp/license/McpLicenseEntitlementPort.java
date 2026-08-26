package com.chatchat.common.mcp.license;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** License entitlement boundary owned by Runtime OS rather than an HTTP client implementation. */
public interface McpLicenseEntitlementPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.license_entitlement.v1";

    AgentPublicationLimit agentPublicationLimit();

    record AgentPublicationLimit(boolean licenseValid, String licenseStatus, String message,
                                 Integer maxPublishedAgents, boolean limited) {
    }
}
