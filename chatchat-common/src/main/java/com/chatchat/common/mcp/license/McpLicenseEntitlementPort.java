package com.chatchat.common.mcp.license;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;
import java.util.Set;

/** License entitlement boundary owned by Runtime OS rather than an HTTP client implementation. */
public interface McpLicenseEntitlementPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.license_entitlement.v1";

    /** Task dispatch requires a current license; unavailable checks must not admit work. */
    default CodingDispatchEntitlement codingDispatchEntitlement() {
        return new CodingDispatchEntitlement(false, "UNAVAILABLE");
    }

    record CodingDispatchEntitlement(boolean licenseValid, String licenseStatus) { }

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

    /** MCP Skills federation is an explicit module and defaults to denied when the license service is unavailable. */
    default SkillFederationEntitlement skillFederationEntitlement() {
        return new SkillFederationEntitlement(false, "UNAVAILABLE",
            "MCP Skills federation entitlement is unavailable", "DEFAULT");
    }

    /** Workspace visibility is licensed centrally by MCP; unavailable entitlement denies access. */
    default WorkspaceEntitlement workspaceEntitlement() {
        return new WorkspaceEntitlement(false, Set.of(), "UNAVAILABLE");
    }

    record WorkspaceEntitlement(boolean licenseValid, Set<String> workspaceIds, String licenseStatus) {
        public boolean allows(String id) {
            // Exploration is an extracted part of the already licensed data-science application.
            return licenseValid && workspaceIds != null && (workspaceIds.contains(id)
                || "data-exploration".equals(id) && workspaceIds.contains("data-science"));
        }
    }

    record AgentPublicationLimit(boolean licenseValid, String licenseStatus, String message,
                                 Integer maxPublishedAgents, boolean limited) {
    }

    record SkillPublicationLimit(boolean licenseValid, String licenseStatus, String message,
                                 Integer maxPublishedSkills, boolean limited, String source) {
    }

    record SkillFederationEntitlement(boolean allowed, String licenseStatus, String message, String source) { }
}
