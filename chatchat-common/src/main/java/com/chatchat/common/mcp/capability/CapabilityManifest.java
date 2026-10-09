package com.chatchat.common.mcp.capability;

import java.util.Map;

/** Governed projection of a real MCP tool; not a replacement MCP execution protocol. */
public record CapabilityManifest(
    String manifestVersion, String capabilityId, String version, String status,
    Map<String, Object> provider, Map<String, Object> discovery,
    Map<String, Object> contract, Map<String, Object> execution,
    Map<String, Object> governance, Map<String, Object> publisherCapabilities
) {
    public static final String V1 = "capability-manifest.v1";
}
