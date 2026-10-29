package com.chatchat.api.datascience;

import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class StudioSessionMigrationTest {
    @Test void backfillsOnlyServerProvenanceAndIsIdempotentAcrossTenants(){
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");var jdbc=new JdbcTemplate(ds);
        jdbc.execute("create table chat_session(session_id varchar,tenant_id varchar,user_id varchar,source_channel varchar,agent_name varchar,mode varchar)");
        jdbc.execute("create table ds_sql_project(session_id varchar,tenant_id varchar,owner_id varchar)");
        jdbc.execute("create table ds_sql_generation_scope(session_id varchar,tenant_id varchar,owner_id varchar)");
        jdbc.update("insert into chat_session values('sql','a','owner','PLATFORM_UI',null,null),('python','a','owner','PLATFORM_UI','PythonStudio','llm_chat'),('chat','a','owner','PLATFORM_UI',null,null),('sql','b','owner','PLATFORM_UI',null,null),('api','a','owner','AGENT_API','PythonStudio','llm_chat')");
        jdbc.update("insert into ds_sql_project values('sql','a','owner')");
        var migration=new StudioSessionMigration(jdbc);migration.migrate();migration.migrate();
        assertThat(jdbc.queryForObject("select count(*) from chat_session",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select source_channel from chat_session where session_id='sql' and tenant_id='a'",String.class)).isEqualTo("SQL_STUDIO");
        assertThat(jdbc.queryForObject("select source_channel from chat_session where session_id='sql' and tenant_id='b'",String.class)).isEqualTo("PLATFORM_UI");
        assertThat(jdbc.queryForObject("select source_channel from chat_session where session_id='python'",String.class)).isEqualTo("PYTHON_STUDIO");
        assertThat(jdbc.queryForObject("select count(*) from chat_session where source_channel='PLATFORM_UI'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select source_channel from chat_session where session_id='api'",String.class)).isEqualTo("AGENT_API");
    }
}
