package com.chatchat.enterprise.workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, String> {
    List<WorkspaceEntity> findByTenantIdOrderBySortOrderAscWorkspaceIdAsc(String tenantId);
    Optional<WorkspaceEntity> findByTenantIdAndWorkspaceId(String tenantId, String workspaceId);
}
