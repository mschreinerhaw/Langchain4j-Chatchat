package com.chatchat.api.workspace;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Idempotent config seeds and one-time equivalent menu access. Never grants tools or assets. */
@Component @RequiredArgsConstructor
public class WorkspaceCompatibilityMigration {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Order(100)
    @Transactional
    public void seed() {
        var populator = new ResourceDatabasePopulator(new ClassPathResource("workspace/workspace-seed.sql"));
        populator.setSqlScriptEncoding("UTF-8"); populator.execute(dataSource);
        var navigation = new ResourceDatabasePopulator(new ClassPathResource("workspace/workspace-navigation.sql"));
        navigation.setSqlScriptEncoding("UTF-8"); navigation.execute(dataSource);
        if (!jdbc.queryForList("select id from sys_permission where permission_code = 'system:workspace-compatibility:v1'").isEmpty()) return;
        bindEquivalent("workspace:api:access", "p.permission_code = 'account:menus:read'");
        bindEquivalent("system:workspaces:manage", "r.role_code = 'SUPER_ADMIN' and p.permission_code = 'system:role:authorize'");
        bindEquivalent("workspace:zhice:access", """
            exists (select 1 from sys_menu_permission mp join sys_menu m on m.id = mp.menu_id
            where mp.permission_id = p.id and m.status = 'enabled')
            """);
        jdbc.update("""
            insert into sys_permission (id, permission_code, permission_name, permission_type, status, sort_order, created_at, updated_at)
            values ('ws-marker', 'system:workspace-compatibility:v1', 'Workspace compatibility migration V1', 'internal', 'enabled', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
    }
    private void bindEquivalent(String target, String maintainedCondition) {
        var rows = jdbc.queryForList("""
            select r.id as role_id, r.tenant_id, target.id as permission_id
            from sys_role r join sys_permission target on target.permission_code = ?
            where r.status = 'enabled' and exists (
                select 1 from sys_role_permission rp join sys_permission p on p.id = rp.permission_id
                where rp.role_id = r.id and rp.tenant_id = r.tenant_id and p.status = 'enabled' and
            """ + maintainedCondition + """
            ) and not exists (select 1 from sys_role_permission existing where existing.role_id = r.id and existing.permission_id = target.id)
            """, target);
        for (var row : rows) {
            String id = UUID.nameUUIDFromBytes((target + ":" + row.get("role_id")).getBytes(StandardCharsets.UTF_8)).toString();
            jdbc.update("insert into sys_role_permission (id, tenant_id, role_id, permission_id, created_at, updated_at) values (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, row.get("tenant_id"), row.get("role_id"), row.get("permission_id"));
        }
    }
}
