package com.chatchat.enterprise.repository.security;

import com.chatchat.enterprise.entity.security.ResourceGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ResourceGrantRepository extends JpaRepository<ResourceGrant, String> {
    boolean existsByTenantIdAndResourceType(String tenantId, String resourceType);
    boolean existsByTenantIdAndResourceTypeAndResourceIdAndPrincipalTypeAndPrincipalIdAndEffectAndEnabledTrue(
        String tenantId, String resourceType, String resourceId, String principalType, String principalId, String effect);
    List<ResourceGrant> findByTenantIdAndResourceTypeAndResourceIdIn(
        String tenantId, String resourceType, Collection<String> resourceIds);

    List<ResourceGrant> findByTenantIdAndResourceTypeOrderByUpdatedAtDesc(String tenantId, String resourceType);
}
