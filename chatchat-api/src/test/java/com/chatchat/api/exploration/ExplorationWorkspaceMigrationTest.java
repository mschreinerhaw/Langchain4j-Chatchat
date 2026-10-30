package com.chatchat.api.exploration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ExplorationWorkspaceMigrationTest {
    @Test void additiveMigrationIsIdempotentAndPreservesCustomizedManifestAndPermissions()throws Exception{
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:explore-migration-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table sys_menu(id varchar primary key,menu_code varchar,menu_name varchar,menu_type varchar,route_path varchar,icon varchar,sort_order int,status varchar,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table sys_menu_permission(id varchar primary key,menu_id varchar,permission_id varchar,created_at timestamp,updated_at timestamp)");
        jdbc.execute("create table workspace_registry(id varchar primary key,manifest_json clob,version bigint,updated_at timestamp)");
        jdbc.update("insert into sys_menu(id,menu_code) values('source','dataScienceData')");
        jdbc.update("insert into sys_menu_permission(id,menu_id,permission_id) values('existing','source','data-science-permission')");
        jdbc.update("insert into workspace_registry(id,manifest_json,version) values('data-science',?,9)","{\"configVersion\":5,\"customSetting\":\"keep\",\"pages\":[{\"id\":\"custom\"}]}");
        var json=new ObjectMapper();var migration=new ExplorationWorkspaceMigration(jdbc,json);migration.migrate();migration.migrate();
        assertThat(jdbc.queryForObject("select count(*) from sys_menu where menu_code='dataExploration'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sys_menu_permission where menu_id='dataExploration' and permission_id='data-science-permission'",Integer.class)).isEqualTo(1);
        var manifest=json.readTree(jdbc.queryForObject("select manifest_json from workspace_registry",String.class));
        assertThat(manifest.path("customSetting").asText()).isEqualTo("keep");assertThat(manifest.path("pages")).hasSize(2);
        assertThat(jdbc.queryForObject("select version from workspace_registry",Long.class)).isEqualTo(10);
        var store=new ExplorationWorkspaceStore(jdbc,json);var owner=new com.chatchat.api.assets.UserAssetService.Owner("tenant","user");
        assertThat(store.get(owner,store.create(owner,"迁移后空间").path("id").asText()).path("title").asText()).isEqualTo("迁移后空间");
    }
}
