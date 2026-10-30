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
 SkillCatalogService catalog=mock(SkillCatalogService.class);EnterpriseAdminService permissions=mock(EnterpriseAdminService.class);
 AgentTaskService tasks=mock(AgentTaskService.class);UiArtifactService artifacts=mock(UiArtifactService.class);ObjectMapper json=new ObjectMapper();
 ExplorationAgentBridge bridge=new ExplorationAgentBridge(catalog,permissions,tasks,artifacts,json);Owner owner=new Owner("tenant","alice");
 SkillDefinition agent=mock(SkillDefinition.class);AgentTaskResponse task=mock(AgentTaskResponse.class);
 @BeforeEach void setup(){when(agent.executionMode()).thenReturn(com.chatchat.chat.interaction.model.InteractionMode.AGENT_CHAT);when(agent.id()).thenReturn("authorized");when(agent.label()).thenReturn("Customer analysis");when(agent.marketStatus()).thenReturn("published");when(catalog.resolve("authorized")).thenReturn(agent);when(permissions.canAccessAgent("alice","authorized")).thenReturn(true);when(task.userId()).thenReturn("alice");when(task.agentId()).thenReturn("authorized");when(task.taskId()).thenReturn("task");when(task.canonicalState()).thenReturn("EXECUTING");when(tasks.get("tenant","task")).thenReturn(Optional.of(task));}
 @Test void onlyPublishedAndAuthorizedAgentsAreOptions(){var draft=mock(SkillDefinition.class);when(draft.marketStatus()).thenReturn("draft");var forbidden=mock(SkillDefinition.class);when(forbidden.id()).thenReturn("forbidden");when(forbidden.marketStatus()).thenReturn("published");when(catalog.list()).thenReturn(List.of(agent,draft,forbidden));assertThat(bridge.options(owner)).extracting(ExplorationAgentBridge.Option::id).containsExactly("authorized");when(permissions.canAccessAgent("alice","authorized")).thenReturn(false);assertThatThrownBy(()->bridge.authorize(owner,"authorized")).hasMessageContaining("403");verifyNoInteractions(tasks);}
 @Test void delegatesToExistingDurableTaskWithStableIdempotencyAndVisualMode(){when(tasks.submit(any())).thenReturn(task);var node=json.createObjectNode().put("id","node").put("agentId","authorized").put("question","Analyze assets");var receipt=bridge.submit(owner,node,null);assertThat(receipt.path("agentTaskId").asText()).isEqualTo("task");assertThat(receipt.path("resultAvailable").asBoolean()).isFalse();verify(tasks).submit(argThat(r->r.getIdempotencyKey().equals("exploration:alice:node")&&r.getSkillId().equals("authorized")&&r.getTenantId().equals("tenant")&&r.getUserId().equals("alice")&&r.getToolInput().get("reportMode").equals("visual")&&r.getAvailableTools().isEmpty()));}
 @Test void readsArchivedVisualReportWithoutSubmittingOrInventingExecutionEvidence(){when(task.canonicalState()).thenReturn("PARTIAL");when(tasks.finalAnswer("tenant","task")).thenReturn(Optional.of("Saved business result"));when(tasks.listEvents("tenant","task",1000)).thenReturn(List.of(AgentEvent.builder().type("COMPLETE").payload("{\"uiResponse\":{\"uiArtifact\":{\"artifactId\":\"artifact\"}}}").build()));when(artifacts.resource("tenant","artifact","visual-report")).thenReturn(Optional.of(Map.of("schemaVersion","python_visual_report.v1","status","PARTIAL","charts",List.of())));var receipt=bridge.snapshot(owner,"task");assertThat(receipt.path("resultAvailable").asBoolean()).isTrue();assertThat(receipt.has("executionMode")).isFalse();assertThat(receipt.path("visualReport").path("charts").size()).isZero();verify(tasks,never()).submit(any());when(task.userId()).thenReturn("bob");assertThatThrownBy(()->bridge.snapshot(owner,"task")).hasMessageContaining("404");}
}
