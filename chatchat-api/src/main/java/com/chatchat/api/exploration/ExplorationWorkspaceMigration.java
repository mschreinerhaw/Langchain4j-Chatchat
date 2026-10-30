package com.chatchat.api.exploration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ExplorationWorkspaceMigration {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    @EventListener(ApplicationReadyEvent.class) @Order(160)
    public void migrate() throws Exception {
        String textType=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection->{
            String product=connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            return product.contains("mysql")||product.contains("mariadb")?"longtext":"text";
        });
        jdbc.execute("create table if not exists runtime_exploration_workspace (id varchar(36) primary key,tenant_id varchar(128) not null,user_id varchar(128) not null,state_json "+textType+" not null,version bigint not null,updated_at bigint not null)");
        jdbc.update("insert into sys_menu(id,menu_code,menu_name,menu_type,route_path,icon,sort_order,status,created_at,updated_at) select 'dataExploration','dataExploration','数据探索','menu','/index.html#/w/data-science/dataExploration','chart-column',35,'enabled',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP where not exists(select 1 from sys_menu where menu_code='dataExploration')");
        // Equivalent navigation only: reuse the exact user-space menu permissions, never grant a role.
        var bindings=jdbc.queryForList("select distinct target.id as menu_id,mp.permission_id from sys_menu source join sys_menu_permission mp on mp.menu_id=source.id join sys_menu target on target.menu_code='dataExploration' where source.menu_code='dataScienceData' and not exists(select 1 from sys_menu_permission e where e.menu_id=target.id and e.permission_id=mp.permission_id)");
        for(var binding:bindings){
            String bindingId=java.util.UUID.nameUUIDFromBytes(("exploration:"+binding.get("menu_id")+":"+binding.get("permission_id")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            jdbc.update("insert into sys_menu_permission(id,menu_id,permission_id,created_at,updated_at) values(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",bindingId,binding.get("menu_id"),binding.get("permission_id"));
        }
        for(String original:jdbc.queryForList("select manifest_json from workspace_registry where id='data-science'",String.class)){
            var manifest=(ObjectNode)json.readTree(original);boolean present=false;
            for(var page:manifest.withArray("pages"))if("dataExploration".equals(page.path("id").asText()))present=true;
            if(present)continue;
            manifest.withArray("pages").addObject().put("id","dataExploration").put("menuCode","dataExploration").put("componentKey","dataExploration").putObject("props");
            manifest.put("configVersion",manifest.path("configVersion").asInt()+1);
            jdbc.update("update workspace_registry set manifest_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id='data-science' and manifest_json=?",json.writeValueAsString(manifest),original);
        }
    }
}
