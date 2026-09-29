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
import com.chatchat.enterprise.repository.security.RoleAgentBindingRepository;
import com.chatchat.enterprise.entity.security.RoleAgentBinding;
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
        when(roles.findByTenantIdAndIdIn(eq("tenant-1"), anyCollection())).thenReturn(List.of(role));
        when(grants.findByTenantIdAndResourceTypeAndResourceIdIn(eq("tenant-1"),
            eq(ResourceAuthorizationPort.SKILL), anyCollection())).thenReturn(List.of(
                grant("skill-a", "ROLE", "analyst", "ALLOW"),
                grant("skill-b", "ROLE", "analyst", "ALLOW"),
                grant("skill-b", "USER", "user-1", "DENY")));
        ResourceAuthorizationService service = new ResourceAuthorizationService(grants, users, memberships, roles, mock(RoleAgentBindingRepository.class));

        assertThat(service.allowedIds(ResourceAuthorizationPort.SKILL, "tenant-1", "user-1",
            Set.of("forged-admin"), Set.of("skill-a", "skill-b", "legacy")))
            .containsExactly("skill-a");
        assertThat(service.explicitlyAllowedIds(ResourceAuthorizationPort.SKILL, "tenant-1", "user-1",
            Set.of("forged-admin"), Set.of("skill-a", "skill-b", "legacy")))
            .containsExactly("skill-a");
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
            mock(SysUserRepository.class), mock(SysUserRoleRepository.class), mock(SysRoleRepository.class), mock(RoleAgentBindingRepository.class));

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

    @Test
    void agentGrantsRequireMatchingAgentAndDatabaseRoleBinding() {
        ResourceGrantRepository grants = mock(ResourceGrantRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        SysUserRoleRepository memberships = mock(SysUserRoleRepository.class);
        SysRoleRepository roles = mock(SysRoleRepository.class);
        RoleAgentBindingRepository bindings = mock(RoleAgentBindingRepository.class);
        SysUser user = new SysUser();
        user.setId("user-1"); user.setTenantId("tenant-1"); user.setStatus("enabled");
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        SysUserRole membership = new SysUserRole();
        membership.setTenantId("tenant-1"); membership.setRoleId("analyst");
        when(memberships.findByUserId("user-1")).thenReturn(List.of(membership));
        SysRole role = new SysRole(); role.setId("analyst"); role.setStatus("enabled");
        when(roles.findByTenantIdAndIdIn(eq("tenant-1"), anyCollection())).thenReturn(List.of(role));
        RoleAgentBinding binding = new RoleAgentBinding();
        binding.setTenantId("tenant-1"); binding.setRoleId("analyst"); binding.setAgentId("agent-a");
        when(bindings.findByRoleIdIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(binding));
        ResourceGrant own = grant("skill-a", "ROLE", "analyst", "ALLOW"); own.setAgentId("agent-a");
        ResourceGrant other = grant("skill-b", "ROLE", "other-role", "ALLOW"); other.setAgentId("agent-a");
        when(grants.findByTenantIdAndResourceTypeAndResourceIdIn(eq("tenant-1"), eq("SKILL"), anyCollection()))
            .thenReturn(List.of(own, other));
        ResourceAuthorizationService service = new ResourceAuthorizationService(grants, users, memberships, roles, bindings);
        Set<String> candidates = Set.of("skill-a", "skill-b");
        assertThat(service.allowedIdsForAgent("SKILL", "tenant-1", "user-1", Set.of("other-role"), candidates, "agent-a"))
            .containsExactly("skill-a");
        assertThat(service.allowedIdsForAgent("SKILL", "tenant-1", "user-1", Set.of(), candidates, "agent-b")).isEmpty();
        assertThat(service.allowedIds("SKILL", "tenant-1", "user-1", Set.of(), candidates)).isEmpty();
        ResourceGrant shared = grant("skill-a", "ROLE", "analyst", "ALLOW");
        own.setEffect("DENY");
        when(grants.findByTenantIdAndResourceTypeAndResourceIdIn(eq("tenant-1"), eq("SKILL"), anyCollection()))
            .thenReturn(List.of(own, shared));
        assertThat(service.allowedIdsForAgent("SKILL", "tenant-1", "user-1", Set.of(), candidates, "agent-a")).isEmpty();
        assertThat(service.allowedIdsForAgent("SKILL", "tenant-1", "user-1", Set.of(), candidates, "agent-b"))
            .containsExactly("skill-a");
        when(grants.findByTenantIdAndResourceTypeAndResourceIdIn(eq("tenant-1"), eq("SKILL"), anyCollection()))
            .thenReturn(List.of(own));
        own.setEffect("ALLOW");
        binding.setEnabled(false);
        assertThat(service.allowedIdsForAgent("SKILL", "tenant-1", "user-1", Set.of(), candidates, "agent-a")).isEmpty();
    }
}
