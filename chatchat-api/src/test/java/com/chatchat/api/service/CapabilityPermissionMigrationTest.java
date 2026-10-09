package com.chatchat.api.service;

import com.chatchat.enterprise.entity.identity.*;
import com.chatchat.enterprise.repository.identity.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.*;

class CapabilityPermissionMigrationTest {
    @Test void onlyEquivalentExistingRoleRightsAreMigratedAndMarkerPreventsRegrant() {
        SysPermissionRepository permissions = mock(SysPermissionRepository.class);
        SysRolePermissionRepository bindings = mock(SysRolePermissionRepository.class);
        SysRoleRepository roles = mock(SysRoleRepository.class);
        when(permissions.findByPermissionCode(anyString())).thenAnswer(call -> {
            String code = call.getArgument(0);
            if (code.equals(CapabilityPermissionMigration.MARKER)) return Optional.empty();
            SysPermission permission = new SysPermission(); permission.setId(code); permission.setPermissionCode(code);
            return Optional.of(permission);
        });
        SysRole role = new SysRole(); role.setId("role"); role.setTenantId("tenant");
        when(roles.findById("role")).thenReturn(Optional.of(role));
        SysRolePermission read = new SysRolePermission();
        read.setRoleId("role"); read.setTenantId("tenant"); read.setPermissionId("mcp:runtime:read");
        when(bindings.findAll()).thenReturn(List.of(read));
        var migration = new CapabilityPermissionMigration(permissions, bindings, roles);
        migration.migrate();
        ArgumentCaptor<SysRolePermission> added = ArgumentCaptor.forClass(SysRolePermission.class);
        verify(bindings, times(2)).save(added.capture());
        assertThat(added.getAllValues()).extracting(SysRolePermission::getPermissionId)
            .containsExactlyInAnyOrder("mcp:capability:read", "mcp:capability:search");
        assertThat(added.getAllValues()).allSatisfy(binding -> assertThat(binding.getTenantId()).isEqualTo("tenant"));
        doReturn(Optional.of(new SysPermission())).when(permissions).findByPermissionCode(CapabilityPermissionMigration.MARKER);
        migration.migrate();
        verify(bindings, times(2)).save(any()); // later manual revocation is not undone on restart
    }
    @Test void rolesWithoutExistingRuntimeRightsReceiveNoNewPermission() {
        SysPermissionRepository permissions = mock(SysPermissionRepository.class);
        SysRolePermissionRepository bindings = mock(SysRolePermissionRepository.class);
        when(permissions.findByPermissionCode(anyString())).thenAnswer(call -> {
            if (CapabilityPermissionMigration.MARKER.equals(call.getArgument(0))) return Optional.empty();
            SysPermission permission = new SysPermission(); permission.setId(call.getArgument(0));
            return Optional.of(permission);
        });
        when(bindings.findAll()).thenReturn(List.of());
        new CapabilityPermissionMigration(permissions, bindings, mock(SysRoleRepository.class)).migrate();
        verify(bindings, never()).save(any());
    }
}
