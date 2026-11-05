package com.chatchat.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Backfills provenance from persisted application/task ownership, never titles or session-name guesses. */
@Component @RequiredArgsConstructor @Slf4j
public class ExplorationConversationOriginMigration {
    public static final String MARKER="system:exploration-conversation-origin:v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @EventListener(ApplicationReadyEvent.class) @Order(180) @Transactional
    public void migrate() throws java.io.IOException {
        if(!jdbc.queryForList("select id from sys_permission where permission_code=?",MARKER).isEmpty())return;
        String after="";int inserted=0;
        while(true) {
            var page=jdbc.query("select id,tenant_id,user_id,state_json from runtime_exploration_workspace where id>? order by id limit 25",
                (rs,index)->java.util.Map.of("id",rs.getString("id"),"tenant_id",rs.getString("tenant_id"),
                    "user_id",rs.getString("user_id"),"state_json",rs.getString("state_json")),after);
            if(page.isEmpty())break;
            for(var workspace:page) {
                after=workspace.get("id").toString();
                String tenant=workspace.get("tenant_id").toString(),user=workspace.get("user_id").toString();
                var state=json.readTree(workspace.get("state_json").toString());
                for(var node:state.path("nodes")) {
                    String task=node.path("receipt").path("agentTaskId").asText();if(task.isBlank())continue;
                    var sessions=jdbc.queryForList("select session_id from agent_task_latest where task_id=? and tenant_id=? and user_id=?",task,tenant,user);
                    for(var row:sessions) {
                        if(row.get("session_id")==null||row.get("session_id").toString().isBlank())continue;
                        String session=row.get("session_id").toString();
                        var owners=jdbc.queryForList("select tenant_id,user_id from chat_session where session_id=?",session);
                        if(owners.stream().anyMatch(owner->!tenant.equals(owner.get("tenant_id"))||!user.equals(owner.get("user_id"))))continue;
                        String id=UUID.nameUUIDFromBytes((tenant+":CONVERSATION:"+session).getBytes(StandardCharsets.UTF_8)).toString();
                        inserted+=jdbc.update("""
                            insert into workspace_resource_origin(id,tenant_id,resource_type,resource_id,workspace_id,creator_user_id,created_at,updated_at)
                            select ?,?,'CONVERSATION',?,'data-exploration',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                            where not exists(select 1 from workspace_resource_origin where tenant_id=? and resource_type='CONVERSATION' and resource_id=?)
                            """,id,tenant,session,user,tenant,session);
                    }
                }
            }
        }
        jdbc.update("""
            insert into sys_permission(id,permission_code,permission_name,permission_type,status,sort_order,created_at,updated_at)
            select ?,?,'Exploration conversation provenance migration','internal','enabled',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            where not exists(select 1 from sys_permission where permission_code=?)
            """,UUID.nameUUIDFromBytes(MARKER.getBytes(StandardCharsets.UTF_8)).toString(),MARKER,MARKER);
        log.info("Exploration conversation provenance backfilled: conversations={}",inserted);
    }
}
