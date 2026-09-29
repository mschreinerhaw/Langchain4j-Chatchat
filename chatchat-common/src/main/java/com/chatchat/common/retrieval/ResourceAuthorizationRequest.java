package com.chatchat.common.retrieval;

import java.util.Set;

/** Candidate IDs supplied by a trusted internal service for a fresh authorization check. */
public record ResourceAuthorizationRequest(
    String tenantId,
    String userId,
    String resourceType,
    Set<String> candidateIds,
    String agentId
) {
    public ResourceAuthorizationRequest(String tenantId, String userId, String resourceType, Set<String> candidateIds) {
        this(tenantId, userId, resourceType, candidateIds, null);
    }
    public ResourceAuthorizationRequest {
        candidateIds = candidateIds == null ? Set.of() : Set.copyOf(candidateIds);
    }
}
