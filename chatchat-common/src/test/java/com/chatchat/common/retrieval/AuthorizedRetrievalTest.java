package com.chatchat.common.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizedRetrievalTest {
    @Test
    void rejectsIndexIdsOutsideDatabaseScopeAndFailedFinalChecks() {
        var scope = AuthorizedRetrieval.Scope.restricted("tenant-1", "user-1", Set.of("allowed", "revoked"));

        List<String> result = AuthorizedRetrieval.select(scope,
            ignored -> List.of("forbidden", "allowed", "revoked", "allowed"),
            id -> id, id -> !"revoked".equals(id), 10);

        assertThat(result).containsExactly("allowed");
    }

    @Test
    void emptyRestrictedScopeDoesNotCallIndex() {
        AtomicBoolean called = new AtomicBoolean();

        List<String> result = AuthorizedRetrieval.select(
            AuthorizedRetrieval.Scope.restricted("tenant-1", "user-1", Set.of()),
            ignored -> { called.set(true); return List.of("stale"); },
            id -> id, id -> true, 10);

        assertThat(result).isEmpty();
        assertThat(called).isFalse();
    }
}
