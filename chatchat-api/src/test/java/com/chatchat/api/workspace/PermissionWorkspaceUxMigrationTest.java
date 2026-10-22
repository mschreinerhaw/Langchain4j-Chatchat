package com.chatchat.api.workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PermissionWorkspaceUxMigrationTest {
    JdbcTemplate fixture(){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:permission_ux_"+UUID.randomUUID()+";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table sys_menu(id varchar primary key,parent_id varchar,menu_code varchar,menu_name varchar,menu_type varchar,route_path varchar,icon varchar,sort_order int,status varchar,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table sys_permission(id varchar primary key,permission_code varchar)");
        jdbc.execute("create table sys_menu_permission(id varchar primary key,menu_id varchar,permission_id varchar,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table workspace_registry(id varchar primary key,manifest_json text,version bigint,updated_at timestamp)");
        jdbc.execute("create table runtime_product_retirement_archive(batch_id varchar,record_type varchar,record_id varchar,saved_payload text,replacement_payload text,created_at timestamp default CURRENT_TIMESTAMP,primary key(batch_id,record_type,record_id))");
        jdbc.execute("create table resource_grant(id varchar,scope varchar)");jdbc.execute("insert into resource_grant values('existing','precise original scope')");
        jdbc.update("insert into sys_permission values('workspace','workspace:permissions:access'),('settings','system:workspaces:manage')");
        for(String name:new String[]{"systemUsers","systemOrganizations","systemRoles","systemResources","systemLogins","systemSkillRoles","permissionTools","workspaces"})jdbc.update("insert into sys_menu(id,menu_code,menu_name,parent_id,status) values(?,?,?,'original-parent','enabled')",name,name,name);
        jdbc.update("insert into sys_menu_permission(id,menu_id,permission_id) values('legacy','systemSkillRoles','workspace')");
        jdbc.update("insert into workspace_registry values('permissions',?,7,CURRENT_TIMESTAMP)","""
          {"entryPage":"systemSkillRoles","layoutType":"assistant","name":"权限管理","configVersion":1,"customSetting":"preserve","pages":[{"id":"systemRoles","componentKey":"systemRoles"},{"id":"systemResources","componentKey":"systemResources"},{"id":"systemSkillRoles","componentKey":"systemSkillRoles"},{"id":"permissionTools","componentKey":"permissionTools"}]}
          """);
        jdbc.update("insert into workspace_registry values('zhice','{\"entryPage\":\"chat\",\"configVersion\":3,\"pages\":[{\"id\":\"chat\",\"componentKey\":\"chat\"}]}',2,CURRENT_TIMESTAMP)");
        return jdbc;
    }
    @Test void consolidatesNavigationRepairsSettingsAccessAndPreservesPoliciesOnce() throws Exception {
        var jdbc=fixture();var migration=new PermissionWorkspaceUxMigration(jdbc,new ObjectMapper());migration.migrate();migration.migrate();
        assertThat(jdbc.queryForObject("select count(*) from sys_menu where menu_code in ('systemSkillRoles','permissionTools')",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_menu where parent_id='permission-workspace-root'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from sys_menu_permission where menu_id='workspaces' and permission_id='settings'",Integer.class)).isOne();
        var json=new ObjectMapper();var manifest=json.readTree(jdbc.queryForObject("select manifest_json from workspace_registry where id='permissions'",String.class));
        assertThat(manifest.path("layoutType").asText()).isEqualTo("management");assertThat(manifest.path("customSetting").asText()).isEqualTo("preserve");assertThat(manifest.path("entryPage").asText()).isEqualTo("systemResources");
        assertThat(jdbc.queryForObject("select scope from resource_grant",String.class)).isEqualTo("precise original scope");assertThat(jdbc.queryForObject("select version from workspace_registry where id='permissions'",Long.class)).isEqualTo(8);
        String customized="{\"pages\":[],\"custom\":true}";jdbc.update("update workspace_registry set manifest_json=? where id='permissions'",customized);migration.migrate();assertThat(jdbc.queryForObject("select manifest_json from workspace_registry where id='permissions'",String.class)).isEqualTo(customized);
    }
    @Test void customChildrenBlockBeforeAnyNavigationChanges() {
        var jdbc=fixture();jdbc.update("insert into sys_menu(id,menu_code,parent_id) values('custom','custom','systemSkillRoles')");
        assertThatThrownBy(()->new PermissionWorkspaceUxMigration(jdbc,new ObjectMapper()).migrate()).hasMessageContaining("custom children");assertThat(jdbc.queryForObject("select count(*) from runtime_product_retirement_archive",Integer.class)).isZero();
    }
}
