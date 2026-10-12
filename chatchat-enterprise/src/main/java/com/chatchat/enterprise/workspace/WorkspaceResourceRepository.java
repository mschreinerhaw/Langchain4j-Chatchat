package com.chatchat.enterprise.workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface WorkspaceResourceRepository extends JpaRepository<WorkspaceResourceBinding, String> {
    Optional<WorkspaceResourceBinding> findByTenantIdAndResourceTypeAndResourceId(String tenant, String type, String id);
}
