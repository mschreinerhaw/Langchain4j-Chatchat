package com.chatchat.common.retrieval;

import java.util.Set;

public record ResourceAuthorizationResult(Set<String> allowedIds) {
    public ResourceAuthorizationResult {
        allowedIds = allowedIds == null ? Set.of() : Set.copyOf(allowedIds);
    }
}
