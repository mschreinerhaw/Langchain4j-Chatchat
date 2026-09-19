package com.chatchat.common.mcp.license;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

/** License entitlement boundary owned by Runtime OS rather than an HTTP client implementation. */
public interface McpLicenseEntitlementPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.license_entitlement.v1";

    /** Uses the same five-publication baseline as domain skills when MCP has no configured entitlement. */
    default AgentPublicationLimit agentPublicationLimit() {
        return new AgentPublicationLimit(true, "DEFAULT", "MCP 未配置 Agent 发布额度，使用默认额度", 5, true);
    }

    /**
     * Returns the domain-skill publication entitlement. Older MCP servers do not
     * expose this capability, so clients must remain compatible with the local
     * five-skill baseline until the entitlement is configured centrally.
     */
    default SkillPublicationLimit skillPublicationLimit() {
        return new SkillPublicationLimit(true, "DEFAULT", "MCP 未配置领域技能发布额度，使用默认额度",
            5, true, "DEFAULT");
    }

    record AgentPublicationLimit(boolean licenseValid, String licenseStatus, String message,
                                 Integer maxPublishedAgents, boolean limited) {
    }

    record SkillPublicationLimit(boolean licenseValid, String licenseStatus, String message,
                                 Integer maxPublishedSkills, boolean limited, String source) {
    }
}
