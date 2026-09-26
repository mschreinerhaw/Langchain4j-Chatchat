package com.chatchat.enterprise.service;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceGrantProvisioningServiceTest {

    @Test
    void createsDatabaseOwnerGrantForAnEnabledTenantUser() {
        ResourceGrantRepository grants = mock(ResourceGrantRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        SysUser user = new SysUser();
        user.setId("user-1");
        user.setTenantId("tenant-1");
        user.setStatus("enabled");
        when(users.findById("user-1")).thenReturn(Optional.of(user));

        ResourceGrantProvisioningService service = new ResourceGrantProvisioningService(grants, users);
        service.ensureUserOwnerGrant(ResourceAuthorizationPort.KNOWLEDGE, "tenant-1", "doc-1", "user-1");

        ArgumentCaptor<ResourceGrant> captor = ArgumentCaptor.forClass(ResourceGrant.class);
        verify(grants).save(captor.capture());
        ResourceGrant saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo("tenant-1");
        assertThat(saved.getResourceType()).isEqualTo(ResourceAuthorizationPort.KNOWLEDGE);
        assertThat(saved.getResourceId()).isEqualTo("doc-1");
        assertThat(saved.getPrincipalType()).isEqualTo("USER");
        assertThat(saved.getPrincipalId()).isEqualTo("user-1");
        assertThat(saved.getEffect()).isEqualTo("ALLOW");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void ignoresLegacyUsernameBecauseItIsNotAValidUserId() {
        ResourceGrantRepository grants = mock(ResourceGrantRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        when(users.findById("admin")).thenReturn(Optional.empty());

        ResourceGrantProvisioningService service = new ResourceGrantProvisioningService(grants, users);
        service.ensureUserOwnerGrant(ResourceAuthorizationPort.KNOWLEDGE, "tenant-1", "doc-1", "admin");

        verify(grants, never()).save(any());
    }
}
