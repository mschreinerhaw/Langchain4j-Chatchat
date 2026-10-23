package com.chatchat.api.workspace;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.security.McpAdministratorPolicy;
import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.identity.SysPermissionRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One-time administrator default for the new resource type; subsequent revocations are preserved. */
@Component @RequiredArgsConstructor
public class TemplatePermissionBootstrap {
    private final SysPermissionRepository markers;
    private final SysRoleRepository roles;
    private final ResourceGrantRepository grants;
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Order(170) @Transactional
    public void migrate() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            if ("PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                    statement.setLong(1, 2026102301L); statement.execute();
                }
            }
            return null;
        });
        for (var role : roles.findAll()) {
            if (!McpAdministratorPolicy.isAdministratorRole(role.getTenantId(), role.getTenantId(), role.getRoleCode(), role.getStatus())) continue;
            String code = "migration:template-grants:" + role.getId();
            if (markers.findByPermissionCode(code).isPresent()) continue;
            boolean exists = grants.findByTenantIdAndResourceTypeOrderByUpdatedAtDesc(role.getTenantId(), ResourceAuthorizationPort.TEMPLATE)
                .stream().anyMatch(grant -> "ROLE".equals(grant.getPrincipalType()) && role.getId().equals(grant.getPrincipalId()) && "*".equals(grant.getResourceId()));
            if (!exists) {
                var grant = new ResourceGrant(); grant.setTenantId(role.getTenantId()); grant.setResourceType(ResourceAuthorizationPort.TEMPLATE);
                grant.setResourceId("*"); grant.setPrincipalType("ROLE"); grant.setPrincipalId(role.getId()); grant.setEffect("ALLOW"); grant.setEnabled(true);
                grants.save(grant);
            }
            var marker = new SysPermission(); marker.setPermissionCode(code);
            marker.setPermissionName("模板权限初始化标记"); marker.setPermissionType("internal"); markers.save(marker);
        }
    }
}
