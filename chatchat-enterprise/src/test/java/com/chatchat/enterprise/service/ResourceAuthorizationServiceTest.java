package com.chatchat.enterprise.service;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.identity.SysUserRole;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceAuthorizationServiceTest {
    @Test
    void usesDatabaseRoleMembershipAndDenyWinsOverAllow() {
        ResourceGrantRepository grants = mock(ResourceGrantRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        SysUserRoleRepository memberships = mock(SysUserRoleRepository.class);
        SysRoleRepository roles = mock(SysRoleRepository.class);
        SysUser user = new SysUser();
        user.setId("user-1"); user.setTenantId("tenant-1"); user.setStatus("enabled");
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        SysUserRole membership = new SysUserRole();
        membership.setTenantId("tenant-1"); membership.setUserId("user-1"); membership.setRoleId("analyst");
        when(memberships.findByUserId("user-1")).thenReturn(List.of(membership));
        SysRole role = new SysRole();
        role.setId("analyst"); role.setStatus("enabled"); role.setRoleCode("analyst");
        when(roles.findByTenantIdOrderByRoleNameAsc("tenant-1")).thenReturn(List.of(role));
        when(grants.findByTenantIdAndResourceTypeAndResourceIdIn(eq("tenant-1"),
            eq(ResourceAuthorizationPort.SKILL), anyCollection())).thenReturn(List.of(
                grant("skill-a", "ROLE", "analyst", "ALLOW"),
                grant("skill-b", "ROLE", "analyst", "ALLOW"),
                grant("skill-b", "USER", "user-1", "DENY")));
        ResourceAuthorizationService service = new ResourceAuthorizationService(grants, users, memberships, roles);

        assertThat(service.allowedIds(ResourceAuthorizationPort.SKILL, "tenant-1", "user-1",
            Set.of("forged-admin"), Set.of("skill-a", "skill-b", "legacy")))
            .containsExactlyInAnyOrder("skill-a", "legacy");
    }

    @Test
    void expiredGrantKeepsResourceRestricted() {
        ResourceGrantRepository grants = mock(ResourceGrantRepository.class);
        ResourceGrant expired = grant("doc-a", "TENANT", "tenant-1", "ALLOW");
        expired.setResourceType(ResourceAuthorizationPort.KNOWLEDGE);
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(grants.findByTenantIdAndResourceTypeAndResourceIdIn(eq("tenant-1"),
            eq(ResourceAuthorizationPort.KNOWLEDGE), anyCollection())).thenReturn(List.of(expired));
        ResourceAuthorizationService service = new ResourceAuthorizationService(grants,
            mock(SysUserRepository.class), mock(SysUserRoleRepository.class), mock(SysRoleRepository.class));

        assertThat(service.allowedIds(ResourceAuthorizationPort.KNOWLEDGE, "tenant-1", null,
            Set.of(), Set.of("doc-a"))).isEmpty();
    }

    private ResourceGrant grant(String id, String principalType, String principalId, String effect) {
        ResourceGrant grant = new ResourceGrant();
        grant.setTenantId("tenant-1"); grant.setResourceType(ResourceAuthorizationPort.SKILL);
        grant.setResourceId(id); grant.setPrincipalType(principalType);
        grant.setPrincipalId(principalId); grant.setEffect(effect);
        return grant;
    }
}
