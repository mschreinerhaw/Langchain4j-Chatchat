package com.chatchat.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ExplorationConversationOriginMigrationTest {
    JdbcTemplate jdbc;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:conversation-origin-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table runtime_exploration_workspace(id varchar primary key,tenant_id varchar,user_id varchar,state_json clob)");
        jdbc.execute("create table agent_task_latest(task_id varchar primary key,tenant_id varchar,user_id varchar,session_id varchar)");
        jdbc.execute("create table chat_session(session_id varchar primary key,tenant_id varchar,user_id varchar)");
        jdbc.execute("create table workspace_resource_origin(id varchar primary key,tenant_id varchar,resource_type varchar,resource_id varchar,workspace_id varchar,creator_user_id varchar,created_at timestamp,updated_at timestamp,unique(tenant_id,resource_type,resource_id))");
        jdbc.execute("create table sys_permission(id varchar primary key,permission_code varchar unique,permission_name varchar,permission_type varchar,status varchar,sort_order int,created_at timestamp,updated_at timestamp)");
    }
    @Test void backfillsOnlyOwnedTaskLinksWithoutGuessingSessionNamesOrChangingHistory() throws Exception {
        String state="{\"nodes\":[{\"receipt\":{\"agentTaskId\":\"owned\"}},{\"receipt\":{\"agentTaskId\":\"other-user\"}},{\"receipt\":{\"agentTaskId\":\"other-tenant\"}}]}";
        jdbc.update("insert into runtime_exploration_workspace values('workspace','tenant','alice',?)",state);
        jdbc.update("insert into agent_task_latest values('owned','tenant','alice','unprefixed-session'),('other-user','tenant','bob','bob-session'),('other-tenant','other','alice','cross-tenant-session')");
        jdbc.update("insert into chat_session values('unprefixed-session','tenant','alice'),('ordinary','tenant','alice'),('bob-session','tenant','bob'),('cross-tenant-session','other','alice')");
        var migration=new ExplorationConversationOriginMigration(jdbc,json);migration.migrate();migration.migrate();
        assertThat(jdbc.queryForObject("select count(*) from workspace_resource_origin",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select resource_id from workspace_resource_origin",String.class)).isEqualTo("unprefixed-session");
        assertThat(jdbc.queryForObject("select workspace_id from workspace_resource_origin",String.class)).isEqualTo("data-exploration");
        assertThat(jdbc.queryForObject("select count(*) from chat_session",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select state_json from runtime_exploration_workspace",String.class)).isEqualTo(state);
        assertThat(jdbc.queryForObject("select count(*) from sys_permission where permission_code=?",Integer.class,ExplorationConversationOriginMigration.MARKER)).isEqualTo(1);
    }
    @Test void preservesExistingProvenanceAndReservesQueuedSessionsBeforeConversationCreation() throws Exception {
        jdbc.update("insert into runtime_exploration_workspace values('workspace','tenant','alice',?)",
            "{\"nodes\":[{\"receipt\":{\"agentTaskId\":\"existing\"}},{\"receipt\":{\"agentTaskId\":\"queued\"}}]}");
        jdbc.update("insert into agent_task_latest values('existing','tenant','alice','existing-session'),('queued','tenant','alice','queued-session')");
        jdbc.update("insert into chat_session values('existing-session','tenant','alice')");
        jdbc.update("insert into workspace_resource_origin values('prior','tenant','CONVERSATION','existing-session','zhice','alice',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        new ExplorationConversationOriginMigration(jdbc,json).migrate();
        assertThat(jdbc.queryForObject("select workspace_id from workspace_resource_origin where resource_id='existing-session'",String.class)).isEqualTo("zhice");
        assertThat(jdbc.queryForObject("select workspace_id from workspace_resource_origin where resource_id='queued-session'",String.class)).isEqualTo("data-exploration");
    }
}
