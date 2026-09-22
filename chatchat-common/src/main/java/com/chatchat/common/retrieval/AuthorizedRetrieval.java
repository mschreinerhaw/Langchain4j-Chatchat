package com.chatchat.common.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Shared scope, recall, and authoritative verification boundary for retrievable resources. */
public final class AuthorizedRetrieval {
    private AuthorizedRetrieval() { }

    public record Scope(String tenantId, String userId, Set<String> roleIds,
                        Set<String> allowedIds, boolean restricted) {
        public Scope {
            roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
            allowedIds = allowedIds == null ? Set.of() : Set.copyOf(allowedIds);
        }

        public static Scope restricted(String tenantId, String userId, Set<String> allowedIds) {
            return restricted(tenantId, userId, Set.of(), allowedIds);
        }

        public static Scope restricted(String tenantId, String userId, Set<String> roleIds,
                                       Set<String> allowedIds) {
            return new Scope(tenantId, userId, roleIds, allowedIds, true);
        }

        public static Scope unrestricted(String tenantId, String userId) {
            return new Scope(tenantId, userId, Set.of(), Set.of(), false);
        }

        public boolean allows(String id) {
            return id != null && !id.isBlank() && (!restricted || allowedIds.contains(id));
        }
    }

    /** The recall callback receives the scope before searching; every returned ID is checked again. */
    public static <T> List<T> select(Scope scope, Function<Scope, List<T>> recall,
                                     Function<T, String> id, Predicate<T> authoritativeCheck,
                                     int limit) {
        if (scope == null || recall == null || id == null || authoritativeCheck == null
            || limit <= 0 || (scope.restricted() && scope.allowedIds().isEmpty())) return List.of();
        List<T> candidates = recall.apply(scope);
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<T> verified = new ArrayList<>();
        for (T candidate : candidates) {
            if (candidate == null) continue;
            String candidateId = id.apply(candidate);
            if (!scope.allows(candidateId) || seen.contains(candidateId)
                || !authoritativeCheck.test(candidate)) continue;
            seen.add(candidateId);
            verified.add(candidate);
            if (verified.size() >= limit) break;
        }
        return List.copyOf(verified);
    }
}
