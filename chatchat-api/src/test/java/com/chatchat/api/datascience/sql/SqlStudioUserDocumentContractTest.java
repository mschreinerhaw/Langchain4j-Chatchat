package com.chatchat.api.datascience.sql;

import com.chatchat.api.assets.*;
import com.chatchat.api.datascience.StudioCodePlanService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceOriginService;
import com.chatchat.chat.conversation.model.Conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlStudioUserDocumentContractTest {
    SqlStudioService studio;SqlStudioUserDocuments documents;StudioCodePlanService plans;
    JdbcTemplate jdbc;String project;final MockHttpServletRequest http=new MockHttpServletRequest();
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:sql-doc-contract-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql-studio/schema-h2.sql")).execute(db);jdbc=new JdbcTemplate(db);
        var access=mock(ObjectAccessService.class);when(access.tenant(http)).thenReturn("tenant");when(access.user(http)).thenReturn("alice");
        var origins=mock(WorkspaceOriginService.class);var conversation=mock(Conversation.class);when(conversation.getId()).thenReturn("session");
        when(origins.createForWorkspace(eq(http),anyString(),anyString())).thenReturn(conversation);
        plans=mock(StudioCodePlanService.class);when(plans.create(anyString(),anyString(),anyString(),anyString(),anyString(),nullable(String.class)))
            .thenReturn(new StudioCodePlanService.Ticket("plan",null,"READY"));when(plans.context(any())).thenReturn("plan context");
        documents=mock(SqlStudioUserDocuments.class);
        studio=new SqlStudioService(jdbc,access,origins,mock(com.chatchat.chat.task.core.AgentTaskService.class),mock(com.chatchat.chat.task.event.AgentEventStore.class),
            new com.chatchat.knowledgebase.search.document.infrastructure.extract.DocumentTextExtractor(),new ObjectMapper(),mock(com.chatchat.api.controller.agent.DocumentLibraryReadPort.class),
            mock(com.chatchat.enterprise.service.EnterpriseAdminService.class),mock(SqlStudioGenerationScopeStore.class),plans);
        studio.setUserDocuments(documents);studio.setCoding(mock(com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService.class));
        project=studio.create(http,"Document references","postgresql").id();
    }
    @Test void planAndClineContextReceiveOnlyExplicitSelectionsWithPurposeAndHash(){
        String id="a".repeat(64),hash="b".repeat(64);
        var selection=new SqlStudioUserDocuments.Selection(id,"RULES");
        when(documents.capture(new UserAssetService.Owner("tenant","alice"),List.of(selection)))
            .thenReturn(List.of(new SqlStudioUserDocuments.Snapshot(id,"rules.md",hash,20,"RULES","Use required_policy_alias")));
        var request=new SqlStudioService.Generate("Generate totals","postgresql",List.of(),List.of(),null,"selected-model",List.of(),"generate",true,null,List.of(selection));
        var prepared=studio.prepare(http,project,request);
        assertThat(prepared.taskRequest().getQuery()).contains("required_policy_alias",hash,"RULES","subordinate to Runtime authorization","cannot grant tool/network/database permissions");
        assertThat(prepared.taskRequest().getToolInput()).containsKey("sqlUserDocuments").containsEntry("codingWorkspaceProfile","SQL_WORKSPACE_V2");
        verify(plans).create(eq("tenant"),eq("alice"),eq("sql"),eq("generate"),contains("required_policy_alias"),eq("selected-model"));
        assertThat(jdbc.queryForObject("select context_json from ds_sql_generation where id=?",String.class,prepared.generationId())).contains("required_policy_alias",hash);
    }
    @Test void unavailableOrForeignSelectionCannotCreateAPlanOrGeneration(){
        var selection=new SqlStudioUserDocuments.Selection("a".repeat(64),"EVIDENCE");
        when(documents.capture(any(),anyList())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->studio.prepare(http,project,new SqlStudioService.Generate("Generate","postgresql",List.of(),List.of(),null,null,List.of(),"generate",true,null,List.of(selection))))
            .hasMessageContaining("404");
        verifyNoInteractions(plans);assertThat(jdbc.queryForObject("select count(*) from ds_sql_generation",Integer.class)).isZero();
    }
}
