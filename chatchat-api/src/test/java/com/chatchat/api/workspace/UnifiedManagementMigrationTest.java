package com.chatchat.api.workspace;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
class UnifiedManagementMigrationTest {
    @Test void repeatableMigrationPreservesDataGrantsAndDoesNotRegrantRevokedManagementRights() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:unified_migration;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("create table sys_permission(id varchar(64) primary key,permission_code varchar(128),permission_name varchar(128),permission_type varchar(32),resource_path varchar(512),http_method varchar(16),status varchar(32),sort_order int,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table sys_role(id varchar(64) primary key,tenant_id varchar(64),role_code varchar(64),status varchar(32))");
        jdbc.execute("create table sys_role_permission(id varchar(64) primary key,tenant_id varchar(64),role_id varchar(64),permission_id varchar(64),created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table sys_menu(id varchar(64) primary key,menu_code varchar(128),menu_name varchar(128),menu_type varchar(32),route_path varchar(512),icon varchar(128),sort_order int,status varchar(32),created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table sys_menu_permission(id varchar(64) primary key,menu_id varchar(64),permission_id varchar(64),created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table workspace_registry(id varchar(64) primary key,status varchar(32),access_permission_code varchar(128),manifest_json clob,default_workspace boolean,version bigint,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table mcp_tool_permission(id varchar(64) primary key,scope varchar(256))");
        jdbc.update("insert into mcp_tool_permission values('existing','exact existing resource scope')");
        jdbc.update("insert into sys_permission(id,permission_code,status) values('authorize','system:role:authorize','enabled')");
        jdbc.update("insert into sys_role values('admin-role','tenant','SUPER_ADMIN','enabled'),('ordinary-role','tenant','USER','enabled')");
        jdbc.update("insert into sys_role_permission(id,tenant_id,role_id,permission_id) values('old-admin','tenant','admin-role','authorize')");
        var migration=new ResourceDatabasePopulator(new ClassPathResource("workspace/unified-management-h2.sql"));migration.setSqlScriptEncoding("UTF-8");
        migration.execute(source);migration.execute(source);
        assertThat(jdbc.queryForObject("select count(*) from workspace_registry",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from sys_role_permission where role_id='ordinary-role'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from mcp_tool_permission",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select scope from mcp_tool_permission",String.class)).isEqualTo("exact existing resource scope");
        jdbc.update("delete from sys_role_permission where permission_id='unified-release-edit'");migration.execute(source);
        assertThat(jdbc.queryForObject("select count(*) from sys_role_permission where permission_id='unified-release-edit'",Integer.class)).isZero();
        jdbc.update("update workspace_registry set manifest_json='custom' where id='permissions'");migration.execute(source);
        assertThat(jdbc.queryForObject("select manifest_json from workspace_registry where id='permissions'",String.class)).isEqualTo("custom");
    }
}
