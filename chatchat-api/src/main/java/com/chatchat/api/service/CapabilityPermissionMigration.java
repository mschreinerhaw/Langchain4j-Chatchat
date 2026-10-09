package com.chatchat.api.service;

import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.entity.identity.SysRolePermission;
import com.chatchat.enterprise.repository.identity.SysPermissionRepository;
import com.chatchat.enterprise.repository.identity.SysRolePermissionRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** One-time API-permission equivalence migration, never a tool grant or a recurring regrant. */
@Component
@RequiredArgsConstructor
public class CapabilityPermissionMigration {
    static final String MARKER = "system:capability-api-permissions:v1";
    private final SysPermissionRepository permissions;
    private final SysRolePermissionRepository bindings;
    private final SysRoleRepository roles;
    @EventListener(ApplicationReadyEvent.class)
    @Order(-100)
    @Transactional
    public void migrate() {
        if (permissions.findByPermissionCode(MARKER).isPresent()) return;
        Map<String, String> equivalent = Map.of("mcp:capability:read", "mcp:runtime:read",
            "mcp:capability:search", "mcp:runtime:read", "mcp:capability:invoke", "mcp:runtime:invoke");
        List<SysRolePermission> existing = bindings.findAll();
        Set<String> seen = new HashSet<>();
        existing.forEach(binding -> seen.add(binding.getRoleId() + ":" + binding.getPermissionId()));
        for (var mapping : equivalent.entrySet()) {
            SysPermission source = permissions.findByPermissionCode(mapping.getValue()).orElseThrow();
            SysPermission target = permissions.findByPermissionCode(mapping.getKey()).orElseThrow();
            if (!"enabled".equalsIgnoreCase(source.getStatus()) || !"enabled".equalsIgnoreCase(target.getStatus())) continue;
            for (SysRolePermission old : existing) {
                if (!source.getId().equals(old.getPermissionId()) || roles.findById(old.getRoleId())
                    .filter(role -> Objects.equals(role.getTenantId(), old.getTenantId())).isEmpty()
                    || !seen.add(old.getRoleId() + ":" + target.getId())) continue;
                SysRolePermission added = new SysRolePermission();
                added.setTenantId(old.getTenantId()); added.setRoleId(old.getRoleId()); added.setPermissionId(target.getId());
                bindings.save(added);
            }
        }
        SysPermission marker = new SysPermission();
        marker.setPermissionCode(MARKER); marker.setPermissionName("Capability API permission migration V1");
        marker.setPermissionType("internal"); permissions.save(marker);
    }
}
