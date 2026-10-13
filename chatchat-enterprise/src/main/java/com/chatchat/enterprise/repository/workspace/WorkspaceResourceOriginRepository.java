package com.chatchat.enterprise.repository.workspace;
import com.chatchat.enterprise.entity.workspace.WorkspaceResourceOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface WorkspaceResourceOriginRepository extends JpaRepository<WorkspaceResourceOrigin, String> {
    Optional<WorkspaceResourceOrigin> findByTenantIdAndResourceTypeAndResourceId(String tenant, String type, String resourceId);
}
