package com.chatchat.enterprise.workspace;

import com.chatchat.enterprise.service.EnterpriseAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Service @RequiredArgsConstructor
public class WorkspaceResourceService {
    private final WorkspaceResourceRepository bindings;
    private final EnterpriseAdminService admin;
    private final com.chatchat.enterprise.repository.identity.SysTenantRepository tenants;
    public boolean isOperator(WorkspaceRequestContext.Scope scope) {
        return scope != null && admin.hasPermission(scope.userId(), "system:admin:operate")
            && admin.hasPermission(scope.userId(), "platform:tasks:all");
    }
    @Transactional(readOnly = true)
    public Optional<WorkspaceResourceBinding> origin(String tenant, String type, String id) {
        return bindings.findByTenantIdAndResourceTypeAndResourceId(tenant, type, id);
    }
    @Transactional(readOnly = true)
    public void assertAccess(WorkspaceRequestContext.Scope scope, String type, String id) {
        if (scope == null || id == null || id.isBlank()) return;
        var row = origin(scope.tenantId(), type, id);
        if (row.isEmpty()) {
            if (!"zhice".equals(scope.workspaceId())) throw new WorkspaceService.AccessDenied();
            return; // Legacy objects keep existing domain/API authorization, with zhice provenance.
        }
        var record = row.get();
        boolean operator = isOperator(scope);
        if (!operator && (!scope.workspaceId().equals(record.getOriginWorkspaceId()) || !scope.userId().equals(record.getOwnerId())))
            throw new WorkspaceService.AccessDenied();
    }
    @Transactional
    public void bind(String tenant, String type, String id, WorkspaceRequestContext.Scope scope) {
        if (scope == null || id == null || id.isBlank()) return;
        if (!tenant.equals(scope.tenantId())) throw new WorkspaceService.AccessDenied();
        tenants.lockById(tenant).orElseThrow(WorkspaceService.AccessDenied::new);
        // Serialize duplicate submissions before dispatch. Never rebind an existing origin.
        var old = origin(tenant, type, id);
        if (old.isPresent()) {
            if (!old.get().getOriginWorkspaceId().equals(scope.workspaceId()) || !old.get().getOwnerId().equals(scope.userId()))
                throw new WorkspaceService.AccessDenied();
            return;
        }
        var row = new WorkspaceResourceBinding(); row.setTenantId(tenant); row.setResourceType(type); row.setResourceId(id);
        row.setOriginWorkspaceId(scope.workspaceId()); row.setOwnerId(scope.userId()); bindings.saveAndFlush(row);
    }
    @Transactional
    public void inherit(String tenant, String fromType, String fromId, String type, String id) {
        origin(tenant, fromType, fromId).ifPresent(from -> bind(tenant, type, id,
            new WorkspaceRequestContext.Scope(tenant, from.getOwnerId(), from.getOriginWorkspaceId())));
    }
}
