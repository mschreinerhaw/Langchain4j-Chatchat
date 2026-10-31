package com.chatchat.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Extracts an existing licensed feature once. Never modifies analysis records or asset grants. */
@Component @RequiredArgsConstructor
public class ExplorationApplicationMigration {
    public static final String MARKER = "system:exploration-workspace:v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @EventListener(ApplicationReadyEvent.class) @Order(170) @Transactional
    public void migrate() throws Exception {
        if (!jdbc.queryForList("select id from sys_permission where permission_code=?", MARKER).isEmpty()) return;
        permission("ws-data-exploration", "workspace:data-exploration:access", "数据探索工作空间访问", "menu", "/api/v1/data-science/explorations/**");
        String manifest = new ClassPathResource("workspace/data-exploration.json").getContentAsString(StandardCharsets.UTF_8);
        jdbc.update("""
            insert into workspace_registry(id,status,access_permission_code,manifest_json,default_workspace,version,created_at,updated_at)
            select 'data-exploration','enabled','workspace:data-exploration:access',?,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            where not exists(select 1 from workspace_registry where id='data-exploration')
            """, manifest);
        // Preserve the intersection which previously admitted users to the data-science application.
        for (var row : jdbc.queryForList("""
            select r.id,r.tenant_id from sys_role r where r.status='enabled'
            and exists(select 1 from sys_role_permission rp join sys_permission p on p.id=rp.permission_id
              where rp.role_id=r.id and rp.tenant_id=r.tenant_id and p.permission_code='capability:data-science' and p.status='enabled')
            and exists(select 1 from workspace_registry w join sys_permission p on p.permission_code=w.access_permission_code
              join sys_role_permission rp on rp.permission_id=p.id
              where w.id='data-science' and w.status='enabled' and rp.role_id=r.id and rp.tenant_id=r.tenant_id and p.status='enabled')
            """)) {
            String role = row.get("id").toString();
            for (String code : new String[]{"workspace:data-exploration:access","workspace:api:access","account:self:read","account:menus:read"}) {
                var ids=jdbc.queryForList("select id from sys_permission where permission_code=? and status='enabled'",String.class,code);
                for(String id:ids) if(jdbc.queryForList("select id from sys_role_permission where role_id=? and permission_id=?",role,id).isEmpty())
                    jdbc.update("insert into sys_role_permission(id,tenant_id,role_id,permission_id,created_at,updated_at) values(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        UUID.nameUUIDFromBytes((MARKER+":"+role+":"+id).getBytes(StandardCharsets.UTF_8)).toString(),row.get("tenant_id"),role,id);
            }
        }
        // This menu now belongs only to the new workspace access permission.
        jdbc.update("delete from sys_menu_permission where menu_id in(select id from sys_menu where menu_code='dataExploration')");
        for(String menu:jdbc.queryForList("select id from sys_menu where menu_code='dataExploration'",String.class))
            jdbc.update("insert into sys_menu_permission(id,menu_id,permission_id,created_at,updated_at) values(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.nameUUIDFromBytes((MARKER+":"+menu).getBytes(StandardCharsets.UTF_8)).toString(),menu,
                jdbc.queryForObject("select id from sys_permission where permission_code='workspace:data-exploration:access'",String.class));
        jdbc.update("update sys_menu set route_path='/index.html#/w/data-exploration/dataExploration' where menu_code='dataExploration'");
        for(var row:jdbc.queryForList("select id,manifest_json from workspace_registry where id<>'data-exploration'")) {
            var old=(ObjectNode)json.readTree(row.get("manifest_json").toString());var pages=json.createArrayNode();
            for(var page:old.path("pages")) if(!"dataExploration".equals(page.path("componentKey").asText()))pages.add(page);
            if(pages.size()==old.path("pages").size())continue;
            old.set("pages",pages);
            if("dataExploration".equals(old.path("entryPage").asText()))old.put("entryPage",pages.isEmpty()?"":pages.get(0).path("id").asText());
            old.put("configVersion",old.path("configVersion").asInt()+1);
            jdbc.update("update workspace_registry set manifest_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id=?",json.writeValueAsString(old),row.get("id"));
        }
        permission("exploration-app-marker",MARKER,"数据探索独立空间迁移 V1","internal",null);
    }
    private void permission(String id,String code,String name,String type,String path) {
        jdbc.update("""
            insert into sys_permission(id,permission_code,permission_name,permission_type,resource_path,http_method,status,sort_order,created_at,updated_at)
            select ?,?,?,?,?, '*','enabled',24,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            where not exists(select 1 from sys_permission where permission_code=?)
            """,id,code,name,type,path,code);
    }
}
