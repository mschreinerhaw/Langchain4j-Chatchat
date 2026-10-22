package com.chatchat.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** One-time product navigation migration. Existing role/resource/tool grants are never changed. */
@Component @RequiredArgsConstructor
public class PermissionWorkspaceUxMigration {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final String MARKER="permission-workspace-ux-v1";
    private static final Set<String> RETIRED=Set.of("systemSkillRoles","permissionTools");
    private boolean postgres;
    @EventListener(ApplicationReadyEvent.class) @Order(150) @Transactional
    public void migrate() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()){postgres="PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());}
        if(postgres)jdbc.execute("select pg_advisory_xact_lock(hashtext('permission-workspace-ux-v1'))");
        if(!jdbc.queryForList("select batch_id from runtime_product_retirement_archive where record_type='MARKER' and record_id=?",MARKER).isEmpty())return;
        if(jdbc.queryForObject("select count(*) from sys_menu where parent_id in (select id from sys_menu where menu_code in ('systemSkillRoles','permissionTools'))",Integer.class)>0)
            throw new IllegalStateException("Retired permission menus have custom children; reparent before migration");
        String batch=UUID.randomUUID().toString();
        archiveRows(batch,"sys_menu","menu_code in ('systemUsers','systemOrganizations','systemRoles','systemResources','systemLogins','systemSkillRoles','permissionTools','workspaces','permissionsRoot')");
        archiveRows(batch,"sys_menu_permission","menu_id in (select id from sys_menu where menu_code in ('systemSkillRoles','permissionTools','workspaces','permissionsRoot'))");
        for(var row:rows("workspace_registry","id in ('permissions','zhice')")) {
            var manifest=(ObjectNode)json.readTree(String.valueOf(row.get("manifest_json")));
            var pages=json.createArrayNode();
            var moved=Set.of("system","systemUsers","systemOrganizations","systemRoles","systemResources","systemLogins");
            for(var page:manifest.path("pages"))if(!RETIRED.contains(page.path("id").asText()) && !RETIRED.contains(page.path("componentKey").asText())
                && !("zhice".equals(row.get("id")) && (moved.contains(page.path("id").asText()) || moved.contains(page.path("componentKey").asText()))))pages.add(page);
            if("permissions".equals(row.get("id"))){manifest.put("layoutType","management");manifest.put("name","权限管理");}
            else if(java.util.stream.StreamSupport.stream(pages.spliterator(),false).noneMatch(page->"workspaces".equals(page.path("id").asText())))pages.add(json.createObjectNode().put("id","workspaces").put("menuCode","workspaces").put("componentKey","workspaces").set("props",json.createObjectNode()));
            manifest.set("pages",pages);
            if(RETIRED.contains(manifest.path("entryPage").asText()))manifest.put("entryPage","systemResources");
            manifest.put("configVersion",manifest.path("configVersion").asInt()+1);
            String replacement=json.writeValueAsString(manifest);
            archive(batch,"workspace_registry",row,replacement);
            jdbc.update("update workspace_registry set manifest_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id=?",replacement,row.get("id"));
        }
        String root=jdbc.queryForList("select id from sys_menu where menu_code='permissionsRoot'",String.class).stream().findFirst().orElse(null);
        if(root==null){root="permission-workspace-root";jdbc.update("insert into sys_menu(id,menu_code,menu_name,menu_type,icon,sort_order,status,created_at,updated_at) values(?,'permissionsRoot','权限管理','directory','shield-check',40,'enabled',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",root);inserted(batch,"sys_menu",root);}
        var labels=Map.of("systemUsers","用户管理","systemOrganizations","组织管理","systemRoles","角色权限","systemResources","资源授权","systemLogins","安全审计");
        for(var entry:labels.entrySet())jdbc.update("update sys_menu set parent_id=?,menu_name=?,route_path=?,updated_at=CURRENT_TIMESTAMP where menu_code=?",root,entry.getValue(),"/index.html#/w/permissions/"+entry.getKey(),entry.getKey());
        jdbc.update("delete from sys_menu_permission where menu_id in (select id from sys_menu where menu_code in ('systemSkillRoles','permissionTools'))");
        jdbc.update("delete from sys_menu where menu_code in ('systemSkillRoles','permissionTools')");
        bind(batch,root,"workspace:permissions:access","permission-ux-root-access");
        var settings=jdbc.queryForList("select id from sys_menu where menu_code='workspaces'",String.class);
        if(settings.isEmpty())throw new IllegalStateException("Workspace settings menu missing from platform seed");
        jdbc.update("update sys_menu set menu_name='工作空间设置',route_path='/index.html#/w/zhice/workspaces',status='enabled',updated_at=CURRENT_TIMESTAMP where id=?",settings.get(0));
        jdbc.update("delete from sys_menu_permission where id='ws-selection-access' and menu_id=? and permission_id in (select id from sys_permission where permission_code='workspace:zhice:access')",settings.get(0));
        bind(batch,settings.get(0),"system:workspaces:manage","permission-ux-settings-access");
        for(var record:jdbc.queryForList("select record_type,record_id from runtime_product_retirement_archive where batch_id=? and record_type in ('sys_menu','INSERTED_sys_menu','INSERTED_sys_menu_permission')",batch)) {
            String table=String.valueOf(record.get("record_type")).replace("INSERTED_","");var replacement=rows(table,"id='"+String.valueOf(record.get("record_id")).replace("'","''")+"'");
            if(!replacement.isEmpty())jdbc.update("update runtime_product_retirement_archive set replacement_payload=? where batch_id=? and record_type=? and record_id=?",saved(replacement.get(0)),batch,record.get("record_type"),record.get("record_id"));
        }
        jdbc.update("insert into runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload) values(?,'MARKER',?,'{}')",batch,MARKER);
    }
    private void bind(String batch,String menu,String permission,String id) throws Exception {
        var targets=jdbc.queryForList("select id from sys_permission where permission_code=?",String.class,permission);
        if(targets.isEmpty())throw new IllegalStateException("Required existing permission missing: "+permission);
        if(jdbc.queryForObject("select count(*) from sys_menu_permission where menu_id=? and permission_id=?",Integer.class,menu,targets.get(0))==0){jdbc.update("insert into sys_menu_permission(id,menu_id,permission_id,created_at,updated_at) values(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,menu,targets.get(0));inserted(batch,"sys_menu_permission",id);}
    }
    private void inserted(String batch,String table,String id){jdbc.update("insert into runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload) values(?,?,?,'{}')",batch,"INSERTED_"+table,id);}
    private List<Map<String,Object>> rows(String table,String condition){return jdbc.queryForList("select t.*"+(postgres?",row_to_json(t)::text as retirement_saved_row":"")+" from "+table+" t where "+condition);}
    private String saved(Map<String,Object> row) throws Exception{return postgres?String.valueOf(row.get("retirement_saved_row")):json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(row);}
    private void archive(String batch,String table,Map<String,Object> row,String replacement) throws Exception{jdbc.update("insert into runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload,replacement_payload) values(?,?,?,?,?)",batch,table,row.get("id"),saved(row),replacement);}
    private void archiveRows(String batch,String table,String condition)throws Exception{for(var row:rows(table,condition))archive(batch,table,row,null);}
}
