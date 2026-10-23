package com.chatchat.api.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

@Component @RequiredArgsConstructor
public class ToolCategoryNavigationMigration {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @EventListener(ApplicationReadyEvent.class) @Order(140)
    @org.springframework.transaction.annotation.Transactional
    public void seed() throws Exception {
        jdbc.update("insert into sys_menu(id,menu_code,menu_name,menu_type,route_path,icon,sort_order,status,created_at,updated_at) select 'toolCategories','toolCategories','分类管理','menu','/index.html#/w/mcp-publication/toolCategories','folder',46,'enabled',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP where not exists(select 1 from sys_menu where menu_code='toolCategories')");
        jdbc.update("insert into sys_menu_permission(id,menu_id,permission_id,created_at,updated_at) select 'tool-categories-read',m.id,p.id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP from sys_menu m join sys_permission p on p.permission_code='mcp:publication:read' where m.menu_code='toolCategories' and not exists(select 1 from sys_menu_permission e where e.menu_id=m.id and e.permission_id=p.id)");
        var rows=jdbc.queryForList("select manifest_json from workspace_registry where id='mcp-publication' for update");
        if(rows.isEmpty())return;
        var manifest=(ObjectNode)json.readTree(String.valueOf(rows.get(0).get("manifest_json")));
        var pages=(ArrayNode)manifest.path("pages");
        for(var page:pages)if("toolCategories".equals(page.path("id").asText()))return;
        pages.addObject().put("id","toolCategories").put("menuCode","toolCategories").put("componentKey","toolCategories").putObject("props");
        manifest.put("configVersion",manifest.path("configVersion").asInt()+1);
        jdbc.update("update workspace_registry set manifest_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id='mcp-publication'",json.writeValueAsString(manifest));
    }
}
