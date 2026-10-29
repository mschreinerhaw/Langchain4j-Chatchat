package com.chatchat.api.datascience.sql;

import com.chatchat.api.datascience.StudioCodePlanService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceOriginService;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlStudioAgentAccessTest {
    SqlStudioService studio;EnterpriseAdminService identities;SkillCatalogService catalog;StudioCodePlanService plans;
    com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService coding;
    JdbcTemplate jdbc;String project;final MockHttpServletRequest http=new MockHttpServletRequest();
    SkillDefinition agent(String id,String status){return new SkillDefinition(id,id,"",List.of(),List.of(),"agent_chat","released-model","","",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null,Map.of(),null,null,List.of(),status,false);}
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:sql-agent-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql-studio/schema-h2.sql")).execute(db);jdbc=new JdbcTemplate(db);
        var access=mock(ObjectAccessService.class);when(access.tenant(http)).thenReturn("tenant");when(access.user(http)).thenReturn("alice");
        var origins=mock(WorkspaceOriginService.class);var conversation=mock(Conversation.class);when(conversation.getId()).thenReturn("session");
        when(origins.createForStudio(eq(http),anyString(),anyString())).thenReturn(conversation);
        plans=mock(StudioCodePlanService.class);when(plans.create(anyString(),anyString(),anyString(),anyString(),anyString(),nullable(String.class)))
            .thenReturn(new StudioCodePlanService.Ticket("plan",null,"READY"));when(plans.context(any())).thenReturn("plan context");
        identities=mock(EnterpriseAdminService.class);catalog=mock(SkillCatalogService.class);
        studio=new SqlStudioService(jdbc,access,origins,mock(com.chatchat.chat.task.core.AgentTaskService.class),mock(com.chatchat.chat.task.event.AgentEventStore.class),
            new com.chatchat.knowledgebase.search.document.infrastructure.extract.DocumentTextExtractor(),new ObjectMapper(),mock(com.chatchat.api.controller.agent.DocumentLibraryReadPort.class),identities,mock(SqlStudioGenerationScopeStore.class),plans);
        ObjectProvider<SkillCatalogService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(catalog);studio.setAgentCatalog(provider);
        coding=mock(com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService.class);studio.setCoding(coding);
        project=studio.create(http,"Agent permissions","postgresql").id();
    }
    SqlStudioService.Generate request(String id){return new SqlStudioService.Generate("Explain or generate SQL","postgresql",List.of(),List.of(),id,null,List.of(),"generate",true);}
    @Test void optionsUsePublishedStatusAndAuthenticatedUserGrants(){
        when(catalog.list()).thenReturn(List.of(agent("own","published"),agent("other","published"),agent("draft","draft")));
        when(identities.canAccessAgent("alice","own")).thenReturn(true);when(identities.canAccessAgent("alice","draft")).thenReturn(true);
        assertThat(studio.agentOptions(http)).extracting(SqlStudioService.AgentOption::id).containsExactly("own");
        when(identities.canAccessAgent("alice","own")).thenReturn(false);assertThat(studio.agentOptions(http)).isEmpty();
    }
    @Test void unauthorizedDraftAndUnknownIdsCannotStartPlanning(){
        when(catalog.list()).thenReturn(List.of(agent("draft","draft")));when(identities.canAccessAgent(anyString(),anyString())).thenReturn(true);
        for(String id:List.of("draft","unknown"))assertThatThrownBy(()->studio.prepare(http,project,request(id))).hasMessageContaining("403");
        when(catalog.list()).thenReturn(List.of(agent("other","published")));when(identities.canAccessAgent("alice","other")).thenReturn(false);
        assertThatThrownBy(()->studio.prepare(http,project,request("other"))).hasMessageContaining("403");
        verifyNoInteractions(plans);assertThat(jdbc.queryForObject("select count(*) from ds_sql_generation",Integer.class)).isZero();
    }
    @Test void publishedSelectionUsesReleasedModelAndRevocationBlocksSubmissionAndResume(){
        var released=agent("own","published");when(catalog.list()).thenReturn(List.of(released));when(catalog.resolve("own")).thenReturn(released);
        when(identities.canAccessAgent("alice","own")).thenReturn(true);
        var prepared=studio.prepare(http,project,request("own"));assertThat(prepared.taskRequest().getModelName()).isEqualTo("released-model");
        when(identities.canAccessAgent("alice","own")).thenReturn(false);
        assertThatThrownBy(()->studio.submitCoding(http,project,prepared.generationId())).hasMessageContaining("403");
        assertThatThrownBy(()->studio.resumeCoding(http,project,prepared.generationId())).hasMessageContaining("403");verifyNoInteractions(coding);
        when(identities.canAccessAgent("alice","own")).thenReturn(true);when(catalog.list()).thenReturn(List.of(agent("own","recalled")));
        assertThatThrownBy(()->studio.submitCoding(http,project,prepared.generationId())).hasMessageContaining("403");
    }
}
