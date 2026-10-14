package com.chatchat.api.datascience.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

/** Application-owned origin index. No tool grants or connection credentials are stored. */
@Component @RequiredArgsConstructor
public class SqlStudioGenerationScopeStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public record Scope(String sessionId,String projectId,String tenantId,String ownerId) { }
    public List<Scope> find(String sessionId) {
        return jdbc.query("select * from ds_sql_generation_scope where session_id=?",
            (row,index)->new Scope(row.getString("session_id"),row.getString("project_id"),row.getString("tenant_id"),row.getString("owner_id")),sessionId);
    }
    public void register(Scope scope) {
        jdbc.update("insert into ds_sql_generation_scope(session_id,project_id,tenant_id,owner_id) "
            + "select ?,?,?,? where not exists(select 1 from ds_sql_generation_scope where session_id=?)",
            scope.sessionId(),scope.projectId(),scope.tenantId(),scope.ownerId(),scope.sessionId());
        if (!find(scope.sessionId()).equals(List.of(scope))) throw new IllegalStateException("SQL generation session scope conflict");
    }
    public void backfill() {
        jdbc.query("select g.context_json,p.id,p.tenant_id,p.owner_id from ds_sql_generation g join ds_sql_project p on p.id=g.project_id",row->{
            try {
                String session=mapper.readTree(row.getString("context_json")).path("sessionId").asText();
                if (session.isBlank()) throw new IllegalStateException("SQL generation has no persisted session");
                register(new Scope(session,row.getString("id"),row.getString("tenant_id"),row.getString("owner_id")));
            } catch (java.io.IOException ex) { throw new IllegalStateException("Invalid persisted SQL generation scope",ex); }
        });
    }
}
