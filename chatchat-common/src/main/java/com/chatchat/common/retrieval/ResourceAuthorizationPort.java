package com.chatchat.common.retrieval;

import java.util.Set;

/** Database-backed resource grants layered on top of each domain's native ACL. */
public interface ResourceAuthorizationPort {
    String KNOWLEDGE = "KNOWLEDGE";
    String MCP_TOOL = "MCP_TOOL";
    String SKILL = "SKILL";

    Set<String> allowedIds(String resourceType, String tenantId, String userId,
                           Set<String> roleIds, Set<String> candidateIds);
}
