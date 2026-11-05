package com.chatchat.api.exploration;
import com.chatchat.api.assets.UserAssetService.Owner;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.task.core.*;
import com.chatchat.chat.task.event.AgentEvent;
import com.chatchat.chat.uiartifact.UiArtifactService;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Execution boundaries are mocked; these tests are not live Agent evidence. */
class ExplorationAgentBridgeTest {
 @Test void projectsIncrementalActivitiesWithoutCopyingToolDataOrSubmittingWork(){
   var started=AgentEvent.builder().sequence(1L).eventId("event-1").type("RUNTIME_OBSERVATION")
     .payload("{\"metadata\":{\"eventKind\":\"CODING_TRACE\",\"payload\":{\"toolCallId\":\"read-1\",\"password\":\"private\"},\"workerActivity\":{\"type\":\"TOOL_STARTED\",\"tool\":\"input_read\",\"source\":\"private program\"}}}").build();
   when(tasks.listEvents("tenant","task",1000)).thenReturn(List.of(started));
   var first=bridge.snapshot(owner,"task");
   assertThat(first.path("analysisEvents").size()).isEqualTo(1);
   assertThat(first.path("analysisEvents").get(0).at("/payload/metadata/workerActivity/toolCallId").asText()).isEqualTo("read-1");
   assertThat(first.path("analysisEvents").toString()).doesNotContain("private","password","source");
   var finished=AgentEvent.builder().sequence(2L).eventId("event-2").type("RUNTIME_OBSERVATION")
     .payload("{\"metadata\":{\"eventKind\":\"CODING_TRACE\",\"workerActivity\":{\"type\":\"TOOL_FINISHED\",\"tool\":\"input_read\",\"toolCallId\":\"read-1\",\"status\":\"SUCCEEDED\"}}}").build();
   when(tasks.listEventsAfter("tenant","task",1L,128)).thenReturn(List.of(finished));
   var next=bridge.snapshot(owner,"task",first);
   assertThat(next.path("analysisEvents").size()).isEqualTo(2);
   assertThat(next.path("eventCursor").asLong()).isEqualTo(2);
   verify(tasks,never()).submit(any());
 }
 @Test void keepsOnlyTheLatestBoundedDisplayEventsWhileRetainingTheJournalCursor(){
   var events=java.util.stream.LongStream.rangeClosed(1,150).mapToObj(sequence->AgentEvent.builder()
       .sequence(sequence).type("STATUS").build()).toList();
   when(tasks.listEvents("tenant","task",1000)).thenReturn(events);
   var snapshot=bridge.snapshot(owner,"task");
   assertThat(snapshot.path("analysisEvents").size()).isEqualTo(120);
   assertThat(snapshot.path("analysisEvents").get(0).path("sequence").asLong()).isEqualTo(31);
   assertThat(snapshot.path("eventCursor").asLong()).isEqualTo(150);
   verify(tasks,never()).submit(any());
 }
 @Test void runningTaskPublishesOnlyArchivedStageArtifactsAndRetainsThemAfterFailure(){
   var collector=mock(com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector.class);bridge.setCollector(collector);
   var event=AgentEvent.builder().sequence(7L).runId("run").type("OBSERVATION").payload("{\"payload\":{\"metadata\":{\"eventKind\":\"CODING_TRACE\",\"taskId\":\"coding\",\"workerActivity\":{\"type\":\"TOOL_FINISHED\",\"tool\":\"python_execute\",\"operationId\":\"operation\",\"resultAvailable\":true,\"resultDatasetRef\":\"runtime-evidence:result\"}}}}").build();
   when(tasks.listEvents("tenant","task",1000)).thenReturn(List.of(event));
   var visual=Map.<String,Object>of("schemaVersion","python_visual_report.v1","charts",List.of(Map.of("id","chart")),"execution",Map.of("taskId","coding","operationId","operation","resultDatasetRef","runtime-evidence:result"));
   when(collector.visualReport(any(),eq("coding"),eq("runtime-evidence:result"))).thenReturn(Optional.of(visual));
   var preview=bridge.snapshot(owner,"task");assertThat(preview.path("resultAvailable").asBoolean()).isTrue();assertThat(preview.path("terminal").asBoolean()).isFalse();assertThat(preview.path("provisional").asBoolean()).isTrue();assertThat(preview.path("eventCursor").asLong()).isEqualTo(7);
   when(task.canonicalState()).thenReturn("FAILED");when(tasks.listEventsAfter("tenant","task",7L,128)).thenReturn(List.of());
   var failed=bridge.snapshot(owner,"task",preview);assertThat(failed.path("visualReport")).isEqualTo(preview.path("visualReport"));assertThat(failed.path("deliveryStatus").asText()).isEqualTo("PRESERVED_AFTER_FAILURE");
   verify(collector,times(1)).visualReport(any(),any(),any());verify(tasks,never()).submit(any());
 }
 @Test void workerClaimWithoutAnArchivedReportDoesNotBecomeAResult(){
   var collector=mock(com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector.class);bridge.setCollector(collector);
   when(tasks.listEvents("tenant","task",1000)).thenReturn(List.of(AgentEvent.builder().runId("run").sequence(1L).type("OBSERVATION").payload("{\"payload\":{\"metadata\":{\"eventKind\":\"CODING_TRACE\",\"taskId\":\"coding\",\"payload\":{\"type\":\"TOOL_FINISHED\",\"tool\":\"python_execute\",\"resultAvailable\":true,\"resultDatasetRef\":\"runtime-evidence:missing\"}}}}").build()));
   assertThat(bridge.snapshot(owner,"task").path("resultAvailable").asBoolean()).isFalse();verifyNoInteractions(artifacts);
 }
 @Test void forwardsPreparedAttachmentsThroughTheExistingUserAssetWorkflow(){
   when(tasks.submit(any())).thenReturn(task);
   var node=json.createObjectNode().put("id","supplemented-node").put("agentId","authorized").put("question","核对补充资料");
   node.putArray("fileIds").add("original").add("supplement");
   var parent=json.createObjectNode().put("question","原资产分析");parent.putObject("receipt").put("answer","历史结论");
   bridge.submit(owner,node,parent);
   verify(tasks).submit(argThat(request->request.getToolInput().get("__userAssetIds").equals(List.of("original","supplement"))&&request.getQuery().contains("优先根据本次明确提供的原始附件")&&request.getQuery().contains("不可信历史数据")));
 }
 SkillCatalogService catalog=mock(SkillCatalogService.class);EnterpriseAdminService permissions=mock(EnterpriseAdminService.class);
 AgentTaskService tasks=mock(AgentTaskService.class);UiArtifactService artifacts=mock(UiArtifactService.class);ObjectMapper json=new ObjectMapper();
 ExplorationAgentBridge bridge=new ExplorationAgentBridge(catalog,permissions,tasks,artifacts,json);Owner owner=new Owner("tenant","alice");
 SkillDefinition agent=mock(SkillDefinition.class);AgentTaskResponse task=mock(AgentTaskResponse.class);
 @BeforeEach void setup(){when(agent.executionMode()).thenReturn(com.chatchat.chat.interaction.model.InteractionMode.AGENT_CHAT);when(agent.id()).thenReturn("authorized");when(agent.label()).thenReturn("Customer analysis");when(agent.marketStatus()).thenReturn("published");when(catalog.resolve("authorized")).thenReturn(agent);when(permissions.canAccessAgent("alice","authorized")).thenReturn(true);when(task.userId()).thenReturn("alice");when(task.agentId()).thenReturn("authorized");when(task.taskId()).thenReturn("task");when(task.canonicalState()).thenReturn("EXECUTING");when(tasks.get("tenant","task")).thenReturn(Optional.of(task));}
 @Test void onlyPublishedAndAuthorizedAgentsAreOptions(){var draft=mock(SkillDefinition.class);when(draft.marketStatus()).thenReturn("draft");var forbidden=mock(SkillDefinition.class);when(forbidden.id()).thenReturn("forbidden");when(forbidden.marketStatus()).thenReturn("published");when(catalog.list()).thenReturn(List.of(agent,draft,forbidden));assertThat(bridge.options(owner)).extracting(ExplorationAgentBridge.Option::id).containsExactly("authorized");when(permissions.canAccessAgent("alice","authorized")).thenReturn(false);assertThatThrownBy(()->bridge.authorize(owner,"authorized")).hasMessageContaining("403");verifyNoInteractions(tasks);}
 @Test void delegatesToExistingDurableTaskWithStableIdempotencyAndVisualMode(){when(tasks.submit(any())).thenReturn(task);var node=json.createObjectNode().put("id","node").put("agentId","authorized").put("question","Analyze assets");var receipt=bridge.submit(owner,node,null);assertThat(receipt.path("agentTaskId").asText()).isEqualTo("task");assertThat(receipt.path("resultAvailable").asBoolean()).isFalse();verify(tasks).submit(argThat(r->r.getIdempotencyKey().equals("exploration:alice:node")&&r.getSkillId().equals("authorized")&&r.getTenantId().equals("tenant")&&r.getUserId().equals("alice")&&r.getToolInput().get("reportMode").equals("visual")&&r.getAvailableTools().isEmpty()));}
 @Test void readsArchivedVisualReportWithoutSubmittingOrInventingExecutionEvidence(){when(task.canonicalState()).thenReturn("PARTIAL");when(tasks.finalAnswer("tenant","task")).thenReturn(Optional.of("Saved business result"));when(tasks.listEvents("tenant","task",1000)).thenReturn(List.of(AgentEvent.builder().type("COMPLETE").payload("{\"uiResponse\":{\"uiArtifact\":{\"artifactId\":\"artifact\"}}}").build()));when(artifacts.resource("tenant","artifact","visual-report")).thenReturn(Optional.of(Map.of("schemaVersion","python_visual_report.v1","status","PARTIAL","charts",List.of())));var receipt=bridge.snapshot(owner,"task");assertThat(receipt.path("resultAvailable").asBoolean()).isTrue();assertThat(receipt.has("executionMode")).isFalse();assertThat(receipt.path("visualReport").path("charts").size()).isZero();verify(tasks,never()).submit(any());when(task.userId()).thenReturn("bob");assertThatThrownBy(()->bridge.snapshot(owner,"task")).hasMessageContaining("404");}
}
