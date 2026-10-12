package com.chatchat.enterprise.workspace;

import org.junit.jupiter.api.Test;
import org.h2.tools.RunScript;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class WorkspaceMigrationTest {
    @Test void h2MigrationIsRepeatableAndOnlyInheritsExistingAccountOrAdminPermission() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:workspace_migration;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("create table sys_permission(id varchar(64) primary key, permission_code varchar(128) unique, permission_name varchar(128), permission_type varchar(32), resource_path varchar(512), http_method varchar(16), sort_order integer, status varchar(32), created_at timestamp, updated_at timestamp)");
            sql.execute("create table sys_role(id varchar(64) primary key, tenant_id varchar(64))");
            sql.execute("create table sys_role_permission(id varchar(64) primary key, tenant_id varchar(64), role_id varchar(64), permission_id varchar(64), created_at timestamp, updated_at timestamp)");
            sql.execute("insert into sys_permission(id,permission_code) values('account','account:menus:read'),('admin','system:admin:operate')");
            sql.execute("insert into sys_role values('reader','a'),('manager','a'),('none','b')");
            sql.execute("insert into sys_role_permission(id,tenant_id,role_id,permission_id) values('r1','a','reader','account'),('r2','a','manager','admin')");
            var script = Path.of("..", "database", "migration", "h2", "V20261013_01__workspace_registry.sql");
            for (int repeat = 0; repeat < 2; repeat++) try (var reader = Files.newBufferedReader(script)) { RunScript.execute(connection, reader); }
            var bindingScript = Path.of("..", "database", "migration", "h2", "V20261013_02__workspace_resource_binding.sql");
            for (int repeat = 0; repeat < 2; repeat++) try (var reader = Files.newBufferedReader(bindingScript)) { RunScript.execute(connection, reader); }
            sql.execute("insert into sys_workspace_resource_binding values('b1','a','task','task-1','zhice','owner',current_timestamp,current_timestamp)");
            assertThatThrownBy(() -> sql.execute("insert into sys_workspace_resource_binding values('b2','a','task','task-1','data-science','owner',current_timestamp,current_timestamp)"))
                .isInstanceOf(java.sql.SQLException.class);
            var count = sql.executeQuery("select count(*) from sys_role_permission"); count.next(); assertThat(count.getInt(1)).isEqualTo(4);
            var forbidden = sql.executeQuery("select count(*) from sys_role_permission where role_id='none'"); forbidden.next(); assertThat(forbidden.getInt(1)).isZero();
            sql.execute("insert into sys_workspace values('w1','a','zhice','{}',null,true,true,0,0,current_timestamp,current_timestamp)");
            assertThatThrownBy(() -> sql.execute("insert into sys_workspace values('w2','a','zhice','{}',null,true,true,0,0,current_timestamp,current_timestamp)"))
                .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
