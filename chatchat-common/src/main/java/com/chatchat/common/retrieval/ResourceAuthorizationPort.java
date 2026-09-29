package com.chatchat.common.retrieval;

import java.util.Set;

/** Database-backed resource grants layered on top of each domain's native ACL. */
public interface ResourceAuthorizationPort {
    String KNOWLEDGE = "KNOWLEDGE";
    String KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    String MCP_TOOL = "MCP_TOOL";
    String SKILL = "SKILL";
    String AGENT_SKILL = "AGENT_SKILL";
    String WORKFLOW = "WORKFLOW";

    Set<String> allowedIds(String resourceType, String tenantId, String userId,
                           Set<String> roleIds, Set<String> candidateIds);

    default boolean hasConfiguredRules(String resourceType, String tenantId) { return false; }

    default Set<String> allowedIdsForAgent(String resourceType, String tenantId, String userId,
                                           Set<String> roleIds, Set<String> candidateIds, String agentId) {
        return allowedIds(resourceType, tenantId, userId, roleIds, candidateIds);
    }

    default Set<String> explicitlyAllowedIdsForAgent(String resourceType, String tenantId, String userId,
                                                     Set<String> roleIds, Set<String> candidateIds, String agentId) {
        return explicitlyAllowedIds(resourceType, tenantId, userId, roleIds, candidateIds);
    }

    default Set<String> explicitlyAllowedIds(String resourceType, String tenantId, String userId,
                                             Set<String> roleIds, Set<String> candidateIds) {
        return Set.of();
    }
}
