package com.chatchat.enterprise.workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface WorkspacePreferenceRepository extends JpaRepository<WorkspacePreference, String> {
    Optional<WorkspacePreference> findByTenantIdAndUserId(String tenantId, String userId);
}
