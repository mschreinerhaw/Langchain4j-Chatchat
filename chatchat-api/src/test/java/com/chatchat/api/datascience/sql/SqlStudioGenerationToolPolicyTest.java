package com.chatchat.api.datascience.sql;
import com.chatchat.agents.runtime.tool.*;
import com.chatchat.common.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SqlStudioGenerationToolPolicyTest {
    private final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:scope_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
    private SqlStudioGenerationToolPolicy policy() throws Exception {
        jdbc.execute("create table ds_sql_project(id varchar primary key,tenant_id varchar,owner_id varchar)");
        jdbc.execute("create table ds_sql_generation(project_id varchar,context_json varchar)");
        jdbc.execute("create table ds_sql_generation_scope(session_id varchar primary key,project_id varchar,tenant_id varchar,owner_id varchar)");
        jdbc.update("insert into ds_sql_project values('p','tenant','owner')");
        jdbc.update("insert into ds_sql_generation values('p',?)",new ObjectMapper().writeValueAsString(Map.of("sessionId","sql-session")));
        var scopes=new SqlStudioGenerationScopeStore(jdbc,new ObjectMapper());scopes.backfill();scopes.backfill();
        return new SqlStudioGenerationToolPolicy(scopes);
    }
    private ToolRuntimeRequest request(String session) {return ToolRuntimeRequest.builder().conversationId(session).tenantId("tenant").userId("owner")
        .toolName("arbitrary_name").allowedTools(List.of("arbitrary_name")).toolInput(ToolInput.builder().parameters(Map.of("sqlStudioGenerationBoundary",Map.of("operation","METADATA_READ","executesBusinessSql",false))).build()).build();}
    private ToolMetadata metadata(String type) {return ToolMetadata.builder().id("arbitrary_name").dataType(type)
        .metadata(Map.of("sqlStudioGenerationBoundary",Map.of("operation","METADATA_READ","executesBusinessSql",false))).build();}
    @Test void safeMetadataDoesNotGrantPermissionsAndOtherSessionsAreUnchanged() throws Exception {
        var policy=policy();assertThat(policy.resolve(request("sql-session"),metadata("UNKNOWN"))).isNull();
        assertThat(policy.resolve(request("other-session"),metadata("ACTION_EXECUTION"))).isNull();
    }
    @Test void executionAndUnknownToolsAreDeniedDespiteAllowListAndForgedFlags() throws Exception {
        var policy=policy();
        assertThat(policy.resolve(request("sql-session"),metadata("DATA_FETCH")).allowed()).isFalse();
        assertThat(policy.resolve(request("sql-session"),metadata("ACTION_EXECUTION")).allowed()).isFalse();
        assertThat(policy.resolve(request("sql-session"),ToolMetadata.builder().id("looks_like_metadata").build()).allowed()).isFalse();
        var request=request("sql-session");request.setTenantId("other");assertThat(policy.resolve(request,metadata("UNKNOWN")).allowed()).isFalse();
    }
    @Test void runtimeDenySurvivesAnEarlierAdministratorAllowAndCallsNoTool() throws Exception {
        var boundary=policy();var registry=mock(ToolRegistry.class);var metadata=metadata("ACTION_EXECUTION");
        when(registry.getToolMetadata("arbitrary_name")).thenReturn(metadata);
        ToolRuntimePolicyProvider admin=(request,meta)->ToolRuntimePolicy.builder().allowed(true).build();
        var runtime=new ToolRuntimeService(registry,new ObjectMapper(),new ToolRuntimeProperties(),List.of(admin,boundary),List.of());
        try {assertThat(runtime.execute(request("sql-session")).output().isSuccess()).isFalse();verify(registry,never()).executeEnhancedTool(any(),any());}
        finally {runtime.shutdown();}
    }
}
