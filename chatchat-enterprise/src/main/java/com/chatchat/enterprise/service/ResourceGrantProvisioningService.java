package com.chatchat.enterprise.service;

import com.chatchat.common.retrieval.ResourceGrantProvisioningPort;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists the owner relationship for newly created resources. */
@Service
@RequiredArgsConstructor
public class ResourceGrantProvisioningService implements ResourceGrantProvisioningPort {

    private static final String USER = "USER";
    private static final String ALLOW = "ALLOW";

    private final ResourceGrantRepository grants;
    private final SysUserRepository users;

    @Override
    @Transactional
    public void ensureUserOwnerGrant(String resourceType, String tenantId, String resourceId, String userId) {
        if (!hasText(resourceType) || !hasText(tenantId) || !hasText(resourceId) || !hasText(userId)) {
            return;
        }
        SysUser user = users.findById(userId.trim()).orElse(null);
        if (user == null || !tenantId.trim().equals(user.getTenantId())
            || !"enabled".equalsIgnoreCase(user.getStatus())) {
            return;
        }
        if (grants.existsByTenantIdAndResourceTypeAndResourceIdAndPrincipalTypeAndPrincipalIdAndEffectAndEnabledTrue(
            tenantId.trim(), resourceType.trim(), resourceId.trim(), USER, userId.trim(), ALLOW)) {
            return;
        }
        ResourceGrant grant = new ResourceGrant();
        grant.setTenantId(tenantId.trim());
        grant.setResourceType(resourceType.trim());
        grant.setResourceId(resourceId.trim());
        grant.setPrincipalType(USER);
        grant.setPrincipalId(userId.trim());
        grant.setEffect(ALLOW);
        grant.setEnabled(true);
        grants.save(grant);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
