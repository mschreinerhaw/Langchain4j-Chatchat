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
    @Test void stageResultsRemainPollableAndCannotBeDeletedOrBranchedUntilCompletion(){
        var state=store.create(alice,"Incremental analysis");String id=state.path("id").asText();
        controller.prepare(http,id,new ExplorationWorkspaceController.Question(0,null,List.of(),"Analyze",null,"authorized"));
        state=store.get(alice,id);String node=nodeId(state);
        var preview=json.createObjectNode().put("agentTaskId","task").put("status","EXECUTING").put("terminal",false).put("resultAvailable",true).put("eventCursor",2);
        preview.putObject("visualReport").put("schemaVersion","python_visual_report.v1").put("title","Stage result").putArray("charts").addObject().put("id","chart").putObject("artifact").put("fileId","real-file");
        when(bridge.submit(eq(alice),any(),isNull())).thenReturn(preview);controller.execute(http,id,node);
        var saved=store.get(alice,id);assertThat(saved.path("nodes").get(0).path("artifactRefs").get(0).path("artifact").path("fileId").asText()).isEqualTo("real-file");
        assertThatThrownBy(()->controller.delete(http,id,new ExplorationWorkspaceController.Delete(saved.path("version").asLong()))).hasMessageContaining("仍有分析任务运行");
        controller.presentation(http,id,node,new ExplorationWorkspaceController.Presentation(saved.path("version").asLong(),true,Map.of()));
        var revision=store.get(alice,id).path("version").asLong();
        assertThatThrownBy(()->controller.prepare(http,id,new ExplorationWorkspaceController.Question(revision,node,List.of(),"Continue",null,"authorized"))).hasMessageContaining("已完成");
        var failed=json.createObjectNode().put("agentTaskId","task").put("terminal",true).put("status","FAILED").put("resultAvailable",false).put("eventCursor",3);
        when(bridge.snapshot(eq(alice),eq("task"),any())).thenReturn(failed);controller.status(http,id,node);
        var result=store.get(alice,id).path("nodes").get(0);assertThat(result.path("receipt").path("resultAvailable").asBoolean()).isTrue();assertThat(result.path("status").asText()).isEqualTo("FAILED");assertThat(result.path("receipt").path("visualReport")).isEqualTo(preview.path("visualReport"));
    }
    @Test void renameAndDeleteAreOwnerScopedAndVersionedWithoutChangingAnalysisHistory(){
        var state=prepared();String id=state.path("id").asText();String nodes=state.path("nodes").toString();
        var renamed=(ObjectNode)controller.rename(http,id,new ExplorationWorkspaceController.Rename(1,"  新项目名称  ")).getData();
        assertThat(renamed.path("title").asText()).isEqualTo("新项目名称");assertThat(renamed.path("version").asLong()).isEqualTo(2);
        assertThat(renamed.path("nodes").toString()).isEqualTo(nodes);
        assertThatThrownBy(()->store.rename(new UserAssetService.Owner("other","alice"),id,2,"越权")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->store.delete(new UserAssetService.Owner("tenant","bob"),id,2)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->store.delete(alice,id,1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->store.rename(alice,id,2,"   ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get(alice,id).path("version").asLong()).isEqualTo(2);
        controller.delete(http,id,new ExplorationWorkspaceController.Delete(2));assertThat(store.list(alice)).isEmpty();
        assertThatThrownBy(()->store.get(alice,id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(analysis);
    }
    @Test void deletingProjectWithPendingAgentTaskIsRejected(){
        var state=prepared();String id=state.path("id").asText();
        ((ObjectNode)state.path("nodes").get(0)).putObject("receipt").put("agentTaskId","running-task").put("terminal",false).put("resultAvailable",false);
        store.save(alice,state,1);
        assertThatThrownBy(()->controller.delete(http,id,new ExplorationWorkspaceController.Delete(2)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("仍有分析任务运行");
        assertThat(store.get(alice,id).path("nodes").size()).isEqualTo(1);
    }
    @Test void agentFollowupUnionsInheritedAndSupplementalFilesAndRejectsUnavailableInput(){
        var state=store.create(alice,"补充数据分析");String id=state.path("id").asText();
        controller.prepare(http,id,new ExplorationWorkspaceController.Question(0,null,List.of("file"),"初始分析",null,"authorized"));
        state=store.get(alice,id);var parent=(ObjectNode)state.withArray("nodes").get(0);parent.put("saved",true);parent.putObject("receipt").put("resultAvailable",true);
        String parentId=parent.path("id").asText();state=store.save(alice,state,1);
        when(assets.get(alice,"supplement")).thenReturn(new UserAssetService.File("supplement","DOCUMENT","补充.csv","text/csv","hash",20,"AVAILABLE","","","upload",0));
        controller.prepare(http,id,new ExplorationWorkspaceController.Question(state.path("version").asLong(),parentId,List.of("supplement"),"使用新增资料核对",null,"authorized"));
        var updated=store.get(alice,id);assertThat(updated.withArray("nodes").get(1).path("fileIds").toString()).isEqualTo("[\"file\",\"supplement\"]");
        when(assets.get(alice,"foreign")).thenThrow(new SecurityException("foreign asset"));
        long version=updated.path("version").asLong();
        assertThatThrownBy(()->controller.prepare(http,id,new ExplorationWorkspaceController.Question(version,parentId,List.of("foreign"),"越权补充",null,"authorized"))).isInstanceOf(SecurityException.class);
        assertThat(store.get(alice,id).withArray("nodes").size()).isEqualTo(2);
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
        when(assets.get(alice,"foreign")).thenThrow(new SecurityException("foreign attachment"));
        var current=store.get(alice,id);
        assertThatThrownBy(()->controller.prepare(http,id,new ExplorationWorkspaceController.Question(current.path("version").asLong(),node,List.of("foreign"),"分析变化原因",null))).isInstanceOf(SecurityException.class);
        controller.prepare(http,id,new ExplorationWorkspaceController.Question(current.path("version").asLong(),node,List.of(),"分析变化原因",null));
        var child=store.get(alice,id).path("nodes").get(1);
        assertThat(child.path("parentId").asText()).isEqualTo(node);assertThat(child.path("fileIds").get(0).asText()).isEqualTo("file");assertThat(child.path("contextIds").get(0).asText()).isEqualTo("ctx");
        verify(assets).get(alice,"foreign");
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
        when(bridge.snapshot(eq(alice),eq("task"),any())).thenReturn(json.createObjectNode().put("agentTaskId","task").put("terminal",true).put("resultAvailable",true).put("answer","Real boundary result"));
        controller.status(http,id,node);controller.status(http,id,node);
        verify(bridge,times(1)).snapshot(eq(alice),eq("task"),any());verify(bridge,never()).submit(any(),any(),any());verifyNoInteractions(analysis);
        var complete=store.get(alice,id);controller.presentation(http,id,node,new ExplorationWorkspaceController.Presentation(complete.path("version").asLong(),true,Map.of()));
        complete=store.get(alice,id);controller.prepare(http,id,new ExplorationWorkspaceController.Question(complete.path("version").asLong(),node,List.of(),"Continue analysis",null,null));
        assertThat(store.get(alice,id).path("nodes").get(1).path("agentId").asText()).isEqualTo("authorized");
    }
}
