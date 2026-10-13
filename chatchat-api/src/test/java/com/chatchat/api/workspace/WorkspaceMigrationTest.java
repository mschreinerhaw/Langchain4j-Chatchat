package com.chatchat.api.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;

class WorkspaceMigrationTest {
    @Test void additiveScriptIsRepeatableAndDoesNotRegrantRevokedPermissions() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:ws_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        // Keep the in-memory database alive across migration connections.
        source.setUrl("jdbc:h2:mem:ws_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("create table sys_permission(id varchar(64) primary key, permission_code varchar(128), permission_name varchar(128), permission_type varchar(32), resource_path varchar(255), http_method varchar(16), status varchar(32), sort_order int, created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table sys_role(id varchar(64) primary key, tenant_id varchar(64), role_code varchar(64), status varchar(32))");
        jdbc.execute("create table sys_role_permission(id varchar(64) primary key, tenant_id varchar(64), role_id varchar(64), permission_id varchar(64), created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table sys_menu(id varchar(64), status varchar(32))");
        jdbc.execute("create table sys_menu_permission(menu_id varchar(64), permission_id varchar(64))");
        jdbc.execute("create table chat_session(session_id varchar(64), user_id varchar(64), payload varchar(128))");
        jdbc.update("insert into chat_session values('history','owner','preserved')");
        jdbc.update("insert into sys_permission(id,permission_code,status) values('menus','account:menus:read','enabled')");
        jdbc.update("insert into sys_role values('existing-role','tenant','USER','enabled')");
        jdbc.update("insert into sys_role_permission(id,tenant_id,role_id,permission_id) values('existing-binding','tenant','existing-role','menus')");
        jdbc.update("insert into sys_menu values('chat','enabled')");
        jdbc.update("insert into sys_menu_permission values('chat','menus')");
        var script = new ResourceDatabasePopulator(new FileSystemResource("../database/migration/h2/V20261013_01__workspace_apps.sql"));
        script.setSqlScriptEncoding("UTF-8"); script.execute(source);
        assertThat(jdbc.queryForObject("select count(*) from workspace_registry", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from sys_role_permission", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from sys_role_permission where permission_id='ws-manage'", Integer.class)).isZero();
        jdbc.update("delete from sys_role_permission where permission_id='ws-api'");
        script.execute(source);
        assertThat(jdbc.queryForObject("select count(*) from sys_role_permission where permission_id='ws-api'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from workspace_registry", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select payload from chat_session where session_id='history'", String.class)).isEqualTo("preserved");
    }
}
