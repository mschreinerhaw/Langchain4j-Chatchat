package com.chatchat.api.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;

@Component @RequiredArgsConstructor
public class UnifiedManagementMigration {
    private final DataSource dataSource;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    @EventListener(ApplicationReadyEvent.class) @Order(130)
    @org.springframework.transaction.annotation.Transactional
    public void seed() throws Exception {
        String dialect;
        try(var connection=dataSource.getConnection()){dialect=connection.getMetaData().getDatabaseProductName();}
        var sql=new ResourceDatabasePopulator(new ClassPathResource("workspace/unified-management"+("H2".equalsIgnoreCase(dialect)?"-h2":"")+".sql"));
        sql.setSqlScriptEncoding("UTF-8");sql.execute(dataSource);
        if(!jdbc.queryForList("select id from sys_permission where permission_code='system:unified-navigation:v1'").isEmpty())return;
        var rows=jdbc.queryForList("select manifest_json from workspace_registry where id='zhice'");
        if(!rows.isEmpty()) {
            var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(String.valueOf(rows.get(0).get("manifest_json")));
            var migrated=java.util.Set.of("system","systemUsers","systemOrganizations","systemRoles","systemResources","systemSkillRoles","systemLogins");
            var pages=json.createArrayNode();
            for(var page:manifest.path("pages")) {
                if(!migrated.contains(page.path("id").asText()) || !migrated.contains(page.path("componentKey").asText()))pages.add(page);
            }
            if(pages.size()!=manifest.path("pages").size()) {
                manifest.set("pages",pages);manifest.put("configVersion",manifest.path("configVersion").asInt()+1);
                if(migrated.contains(manifest.path("entryPage").asText()) && !pages.isEmpty())manifest.put("entryPage",pages.get(0).path("id").asText());
                jdbc.update("update workspace_registry set manifest_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id='zhice'",json.writeValueAsString(manifest));
            }
        }
        jdbc.update("insert into sys_permission(id,permission_code,permission_name,permission_type,status,sort_order,created_at,updated_at) values('unified-navigation-marker','system:unified-navigation:v1','Unified navigation V1','internal','enabled',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
}
