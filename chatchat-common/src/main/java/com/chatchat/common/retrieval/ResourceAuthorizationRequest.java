package com.chatchat.common.retrieval;

import java.util.Set;

/** Candidate IDs supplied by a trusted internal service for a fresh authorization check. */
public record ResourceAuthorizationRequest(
    String tenantId,
    String userId,
    String resourceType,
    Set<String> candidateIds
) {
    public ResourceAuthorizationRequest {
        candidateIds = candidateIds == null ? Set.of() : Set.copyOf(candidateIds);
    }
}
