package com.chatchat.api.exploration;

import com.chatchat.api.assets.*;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real SQL persistence, mocked execution boundary. These are NOT live Coding acceptance evidence. */
class ExplorationWorkspaceTest {
    JdbcTemplate jdbc;ObjectMapper json=new ObjectMapper();ExplorationWorkspaceStore store;
    UserAssetService assets;AssetAnalysisController analysis;ObjectAccessService access;ExplorationWorkspaceController controller;
    ExplorationAgentBridge bridge=mock(ExplorationAgentBridge.class);
    com.chatchat.api.workspace.WorkspaceService workspaces;
    MockHttpServletRequest http=new MockHttpServletRequest();UserAssetService.Owner alice=new UserAssetService.Owner("tenant","alice");
    @BeforeEach void setup(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:exploration-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table runtime_exploration_workspace(id varchar primary key,tenant_id varchar,user_id varchar,state_json clob,version bigint,updated_at bigint)");
        store=new ExplorationWorkspaceStore(jdbc,json);assets=mock(UserAssetService.class);analysis=mock(AssetAnalysisController.class);access=mock(ObjectAccessService.class);
        when(access.tenant(http)).thenReturn("tenant");when(access.user(http)).thenReturn("alice");
        when(assets.get(eq(alice),eq("file"))).thenReturn(new UserAssetService.File("file","DOCUMENT","customer.csv","text/csv","hash",10,"AVAILABLE","","","upload",0));
        workspaces=mock(com.chatchat.api.workspace.WorkspaceService.class);
        controller=new ExplorationWorkspaceController(store,access,assets,analysis,json,bridge,workspaces);
    }
    @Test void requiresTheExplorationApplicationEvenWhenTheClientClaimsAnotherWorkspace() {
        http.addHeader("X-Workspace-Id","data-science");
        doThrow(new SecurityException("Workspace access denied")).when(workspaces).requireAvailable(http,"data-exploration");
        assertThatThrownBy(()->controller.list(http)).isInstanceOf(SecurityException.class);
        verify(workspaces).requireAvailable(http,"data-exploration");
    }
    ObjectNode prepared(){
        var space=store.create(alice,"客户账户分析");
        controller.prepare(http,space.path("id").asText(),new ExplorationWorkspaceController.Question(0,null,List.of("file"),"分析资产结构",null));
        return store.get(alice,space.path("id").asText());
    }
    String nodeId(ObjectNode space){return space.path("nodes").get(0).path("id").asText();}
    ObjectNode complete(){
        var space=prepared();doReturn(ApiResponse.success(Map.of("resultAvailable",true,"executionMode","PYTHON","taskId","task","evidenceRefs",List.of("test-boundary-ref"),"visualReport",Map.of("charts",List.of(Map.of("id","chart","kind","plotly")))))).when(analysis).analyzeVisual(eq(http),any());
        controller.execute(http,space.path("id").asText(),nodeId(space));return store.get(alice,space.path("id").asText());
    }
    @Test void sqlReloadSurvivesStoreRecreationAndRejectsOtherOwnersAndStaleWrites(){
        var space=prepared();var id=space.path("id").asText();
        assertThat(new ExplorationWorkspaceStore(jdbc,json).get(alice,id)).isEqualTo(space);
        assertThatThrownBy(()->store.get(new UserAssetService.Owner("tenant","bob"),id)).hasMessageContaining("404");
        assertThatThrownBy(()->store.get(new UserAssetService.Owner("other","alice"),id)).hasMessageContaining("404");
        assertThatThrownBy(()->store.save(alice,space,0)).hasMessageContaining("409");verifyNoInteractions(analysis);
    }
    @Test void preparesBeforeDispatchAndReusesStableRequestForRecovery(){
        var space=prepared();String id=space.path("id").asText(),node=nodeId(space);
        verifyNoInteractions(analysis);
        doReturn(ApiResponse.success(Map.of("resultAvailable",false,"status","RUNNING"))).when(analysis).analyzeVisual(eq(http),any());
        controller.execute(http,id,node);controller.execute(http,id,node);
        verify(analysis,times(2)).analyzeVisual(eq(http),argThat(body->body.requestId().equals(node)&&body.fileIds().equals(List.of("file"))&&body.instruction().equals("分析资产结构")));
        assertThat(store.get(alice,id).path("nodes").get(0).path("saved").asBoolean()).isFalse();
    }
    @Test void presentationAndReloadDoNotInvokeAIOrMutateReceipt(){
        var space=complete();String id=space.path("id").asText(),node=nodeId(space);var original=space.path("nodes").get(0).path("receipt").deepCopy();clearInvocations(analysis);
        controller.presentation(http,id,node,new ExplorationWorkspaceController.Presentation(space.path("version").asLong(),true,Map.of("chart","line")));
        var restored=new ExplorationWorkspaceStore(jdbc,json).get(alice,id).path("nodes").get(0);
        assertThat(restored.path("chartTypes").path("chart").asText()).isEqualTo("line");assertThat(restored.path("saved").asBoolean()).isTrue();assertThat(restored.path("receipt")).isEqualTo(original);
        controller.execute(http,id,node);verifyNoInteractions(analysis);
    }
    @Test void childInheritsOnlySavedParentFilesAndArchivedContext(){
        var space=complete();String id=space.path("id").asText(),node=nodeId(space);
        assertThatThrownBy(()->controller.prepare(http,id,new ExplorationWorkspaceController.Question(space.path("version").asLong(),node,List.of("other"),"继续分析",null))).hasMessageContaining("保存");
        controller.presentation(http,id,node,new ExplorationWorkspaceController.Presentation(space.path("version").asLong(),true,Map.of()));
        when(assets.listPage(alice,UserAssetService.Kind.CONTEXT,"task",100,0)).thenReturn(List.of(new UserAssetService.File("ctx","CONTEXT","context.json","application/json","sha",100,"AVAILABLE","task","run","user-analysis-result",0)));
        var current=store.get(alice,id);controller.prepare(http,id,new ExplorationWorkspaceController.Question(current.path("version").asLong(),node,List.of("foreign"),"分析变化原因",null));
        var child=store.get(alice,id).path("nodes").get(1);
        assertThat(child.path("parentId").asText()).isEqualTo(node);assertThat(child.path("fileIds").get(0).asText()).isEqualTo("file");assertThat(child.path("contextIds").get(0).asText()).isEqualTo("ctx");
        verify(assets,never()).get(any(),eq("foreign"));
    }
    @Test void rejectedExecutionPersistsRecoverableStateAndCannotBeSaved(){
        var space=prepared();String id=space.path("id").asText(),node=nodeId(space);
        when(analysis.analyzeVisual(eq(http),any())).thenThrow(new IllegalStateException("sandbox unavailable"));
        assertThatThrownBy(()->controller.execute(http,id,node)).hasMessageContaining("sandbox unavailable");
        var current=store.get(alice,id);assertThat(current.path("nodes").get(0).path("status").asText()).isEqualTo("RECOVERABLE");
        assertThatThrownBy(()->controller.presentation(http,id,node,new ExplorationWorkspaceController.Presentation(current.path("version").asLong(),true,Map.of()))).hasMessageContaining("尚无");
    }
    @Test void invalidConfigOrForeignNodeCannotChangeEvidence(){
        var space=complete();String id=space.path("id").asText(),node=nodeId(space);long version=space.path("version").asLong();
        assertThatThrownBy(()->controller.presentation(http,id,node,new ExplorationWorkspaceController.Presentation(version,true,Map.of("foreign-chart","line")))).hasMessageContaining("无效");
        assertThatThrownBy(()->controller.presentation(http,id,node,new ExplorationWorkspaceController.Presentation(version,true,Map.of("chart","javascript")))).hasMessageContaining("无效");
        when(access.user(http)).thenReturn("bob");assertThatThrownBy(()->controller.execute(http,id,node)).hasMessageContaining("404");
    }
    @Test void httpRoutesBindExplicitPathNamesWithoutCompilerParameterMetadata()throws Exception{
        when(access.tenant(any())).thenReturn("tenant");when(access.user(any())).thenReturn("alice");
        var space=complete();String id=space.path("id").asText(),node=nodeId(space);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/data-science/explorations/"+id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/data-science/explorations/"+id+"/nodes/"+node+"/execute").contentType("application/json").content("{}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/data-science/explorations/"+id+"/nodes/"+node).contentType("application/json")
            .content(json.writeValueAsString(new ExplorationWorkspaceController.Presentation(space.path("version").asLong(),true,Map.of("chart","line")))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var current=store.get(alice,id);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/data-science/explorations/"+id+"/nodes").contentType("application/json")
            .content(json.writeValueAsString(new ExplorationWorkspaceController.Question(current.path("version").asLong(),null,List.of("file"),"重新分析",null))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test void newWorkspaceRetainsAuthorizedSourceReferencesAcrossReloadAndRootPreparation(){
        var source=prepared();String sourceId=source.path("id").asText();
        controller.create(http,new ExplorationWorkspaceController.Create("分支分析",sourceId,nodeId(source)));
        var created=store.list(alice).stream().filter(n->"分支分析".equals(n.path("title").asText())).findFirst().orElseThrow();
        String id=created.path("id").asText();var restored=new ExplorationWorkspaceStore(jdbc,json).get(alice,id);
        assertThat(restored.path("fileIds").get(0).asText()).isEqualTo("file");
        controller.prepare(http,id,new ExplorationWorkspaceController.Question(0,null,null,"分析结构",null));
        assertThat(store.get(alice,id).path("nodes").get(0).path("fileIds").get(0).asText()).isEqualTo("file");
        verifyNoInteractions(analysis);
        when(access.user(http)).thenReturn("bob");
        assertThatThrownBy(()->controller.create(http,new ExplorationWorkspaceController.Create("foreign",sourceId,nodeId(source)))).hasMessageContaining("404");
    }
    @Test void agentEntryNeedsNoFileSelectionAndStatusNeverSubmitsAnotherTask(){
        var space=store.create(alice,"Agent analysis");String id=space.path("id").asText();
        controller.prepare(http,id,new ExplorationWorkspaceController.Question(0,null,List.of(),"Analyze customer assets",null,"authorized"));
        var prepared=store.get(alice,id);String node=nodeId(prepared);
        assertThat(prepared.path("nodes").get(0).path("agentId").asText()).isEqualTo("authorized");
        assertThat(prepared.path("nodes").get(0).path("fileIds").size()).isZero();verifyNoInteractions(assets,analysis);
        when(bridge.submit(eq(alice),any(),isNull())).thenReturn(json.createObjectNode().put("agentTaskId","task").put("status","EXECUTING").put("resultAvailable",false));
        controller.execute(http,id,node);clearInvocations(bridge);
        when(bridge.snapshot(alice,"task")).thenReturn(json.createObjectNode().put("agentTaskId","task").put("terminal",true).put("resultAvailable",true).put("answer","Real boundary result"));
        controller.status(http,id,node);controller.status(http,id,node);
        verify(bridge,times(1)).snapshot(alice,"task");verify(bridge,never()).submit(any(),any(),any());verifyNoInteractions(analysis);
        var complete=store.get(alice,id);controller.presentation(http,id,node,new ExplorationWorkspaceController.Presentation(complete.path("version").asLong(),true,Map.of()));
        complete=store.get(alice,id);controller.prepare(http,id,new ExplorationWorkspaceController.Question(complete.path("version").asLong(),node,List.of(),"Continue analysis",null,null));
        assertThat(store.get(alice,id).path("nodes").get(1).path("agentId").asText()).isEqualTo("authorized");
    }
}
