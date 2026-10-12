package com.chatchat.chat.workspace;

import com.chatchat.enterprise.workspace.*;
import org.springframework.data.jpa.domain.Specification;

/** Filters before pagination; legacy records remain in zhice with their existing domain ACLs. */
public final class WorkspaceVisibility {
    private WorkspaceVisibility() {}
    public static <T> Specification<T> of(String type, String idField, WorkspaceRequestContext.Scope scope, boolean operator) {
        return (root, query, cb) -> {
            if (scope == null || operator) return cb.conjunction();
            var any = query.subquery(String.class); var anyRow = any.from(WorkspaceResourceBinding.class);
            any.select(anyRow.get("id")).where(cb.equal(anyRow.get("tenantId"), root.get("tenantId")),
                cb.equal(anyRow.get("resourceType"), type), cb.equal(anyRow.get("resourceId"), root.get(idField)));
            var owned = query.subquery(String.class); var ownedRow = owned.from(WorkspaceResourceBinding.class);
            owned.select(ownedRow.get("id")).where(cb.equal(ownedRow.get("tenantId"), scope.tenantId()),
                cb.equal(ownedRow.get("resourceType"), type), cb.equal(ownedRow.get("resourceId"), root.get(idField)),
                cb.equal(ownedRow.get("originWorkspaceId"), scope.workspaceId()), cb.equal(ownedRow.get("ownerId"), scope.userId()));
            return cb.and(cb.equal(root.get("tenantId"), scope.tenantId()),
                cb.or(cb.and(cb.literal("zhice".equals(scope.workspaceId())), cb.not(cb.exists(any))), cb.exists(owned)));
        };
    }
}
