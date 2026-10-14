package com.chatchat.api.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;

class WorkspaceMigrationTest {
    @Test void workshopBrandingUpgradesOnceAndPreservesCustomWorkspace() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:ws_branding;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("create table workspace_registry(id varchar(64) primary key, manifest_json clob, version bigint, updated_at timestamp)");
        jdbc.execute("create table sys_menu(menu_code varchar(128), menu_name varchar(128), updated_at timestamp)");
        jdbc.execute("create table sys_permission(permission_code varchar(128), permission_name varchar(128), updated_at timestamp)");
        var upgrade = new org.springframework.core.io.ClassPathResource("sql-studio/workspace-upgrade.sql");
        String sql;
        try (var input = upgrade.getInputStream()) { sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        var matcher = java.util.regex.Pattern.compile("SET manifest_json='([^']+)'").matcher(sql);
        assertThat(matcher.find()).isTrue();
        String original = matcher.group(1);
        jdbc.update("insert into workspace_registry values('data-science', ?, 7, CURRENT_TIMESTAMP)", original);
        jdbc.update("insert into sys_menu values('dataScience', '数据科学', CURRENT_TIMESTAMP)");
        jdbc.update("insert into sys_permission values('capability:data-science', '数据科学', CURRENT_TIMESTAMP)");
        var script = new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("workspace/workspace-branding.sql"));
        script.setSqlScriptEncoding("UTF-8"); script.execute(source); script.execute(source);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest = mapper.readTree(jdbc.queryForObject("select manifest_json from workspace_registry", String.class));
        assertThat(manifest.path("name").asText()).isEqualTo("数据车间");
        assertThat(manifest.path("configVersion").asInt()).isEqualTo(4);
        assertThat(manifest.path("pages")).isEqualTo(mapper.readTree(original).path("pages"));
        assertThat(jdbc.queryForObject("select version from workspace_registry", Long.class)).isEqualTo(8L);
        assertThat(jdbc.queryForObject("select menu_name from sys_menu", String.class)).isEqualTo("数据车间");
        assertThat(jdbc.queryForObject("select permission_name from sys_permission", String.class)).isEqualTo("数据车间");
        var customized = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original);
        customized.put("name", "自定义工作台");
        jdbc.update("update workspace_registry set manifest_json=?", mapper.writeValueAsString(customized));
        script.execute(source);
        assertThat(mapper.readTree(jdbc.queryForObject("select manifest_json from workspace_registry", String.class))).isEqualTo(customized);
        assertThat(jdbc.queryForObject("select version from workspace_registry", Long.class)).isEqualTo(8L);
    }
    @Test void managementMenuAndLoginDefaultUpgradeOnceAndPreserveLaterConfiguration() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:ws_management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("create table sys_permission(id varchar(64) primary key, permission_code varchar(128), permission_name varchar(128), permission_type varchar(32), resource_path varchar(255), http_method varchar(16), status varchar(32), sort_order int, created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table sys_menu(id varchar(64) primary key, parent_id varchar(64), menu_code varchar(128), menu_name varchar(128), menu_type varchar(32), route_path varchar(512), icon varchar(128), sort_order int, status varchar(32), created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table sys_menu_permission(id varchar(64) primary key, menu_id varchar(64), permission_id varchar(64), created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table workspace_registry(id varchar(64) primary key, status varchar(32), access_permission_code varchar(128), manifest_json clob, default_workspace boolean, version bigint, created_at timestamp, updated_at timestamp)");
        jdbc.update("insert into sys_menu(id,menu_code) values('platform-root','platform')");
        var seed = new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("workspace/workspace-seed.sql"),
            new org.springframework.core.io.ClassPathResource("workspace/workspace-navigation.sql"));
        seed.setSqlScriptEncoding("UTF-8"); seed.execute(source);
        jdbc.update("update workspace_registry set default_workspace = (id = 'data-science')");
        var script = new ResourceDatabasePopulator(new FileSystemResource("../database/migration/h2/V20261014_01__workspace_management.sql"));
        script.setSqlScriptEncoding("UTF-8"); script.execute(source);
        assertThat(jdbc.queryForObject("select parent_id from sys_menu where menu_code='workspaces'", String.class)).isEqualTo("platform-root");
        assertThat(jdbc.queryForObject("select id from workspace_registry where default_workspace=true", String.class)).isEqualTo("zhice");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest = mapper.readTree(jdbc.queryForObject("select manifest_json from workspace_registry where id='zhice'", String.class));
        assertThat(manifest.path("pages").toString()).contains("workspaces");
        long version = jdbc.queryForObject("select version from workspace_registry where id='zhice'", Long.class);
        jdbc.update("update workspace_registry set default_workspace = (id = 'data-science')");
        jdbc.update("update sys_menu set menu_name='Custom workspace menu' where menu_code='workspaces'");
        jdbc.update("delete from sys_menu_permission where menu_id='ws-selection-menu'");
        script.execute(source);
        assertThat(jdbc.queryForObject("select id from workspace_registry where default_workspace=true", String.class)).isEqualTo("data-science");
        assertThat(jdbc.queryForObject("select version from workspace_registry where id='zhice'", Long.class)).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from sys_menu where menu_code='workspaces'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select menu_name from sys_menu where menu_code='workspaces'", String.class)).isEqualTo("Custom workspace menu");
        assertThat(jdbc.queryForObject("select count(*) from sys_menu_permission where menu_id='ws-selection-menu'", Integer.class)).isZero();
    }
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
