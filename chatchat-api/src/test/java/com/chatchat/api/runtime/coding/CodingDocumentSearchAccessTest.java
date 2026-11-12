package com.chatchat.api.runtime.coding;

import com.chatchat.api.runtime.coding.knowledge.CodingDocumentSearchAccess;
import com.chatchat.agents.runtime.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.retrieval.*;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.tool.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingDocumentSearchAccessTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final SkillExecutionScopePort scopes=mock(SkillExecutionScopePort.class);
    final AgentResourceScopePort resources=mock(AgentResourceScopePort.class);
    final ResourceAuthorizationPort grants=mock(ResourceAuthorizationPort.class);
    final ToolRegistry registry=mock(ToolRegistry.class);
    final McpToolCatalogQueryPort catalog=mock(McpToolCatalogQueryPort.class);
    final ToolRuntimeService runtime=mock(ToolRuntimeService.class);
    final CodingDocumentSearchAccess service=new CodingDocumentSearchAccess(scopes,resources,grants,registry,catalog,runtime,json);
    final KernelDataScope scope=new KernelDataScope("tenant","user",null,null,"run",null,Map.of());
    final CodingTaskContract contract=mock(CodingTaskContract.class,RETURNS_DEEP_STUBS);
    final String tool=CodingDocumentSearchAccess.LOCAL_TOOL;
    final SkillExecutionScopePort.EffectiveScope effective=new SkillExecutionScopePort.EffectiveScope(List.of("doc-1"),List.of(),List.of("reader"),true,true);
    @BeforeEach void setup(){
        when(scopes.resolve("tenant","user","agent",List.of(),List.of())).thenReturn(effective);
        when(resources.resolve("tenant","user","agent",false)).thenReturn(Set.of(AgentResourcePolicy.KNOWLEDGE));
        when(grants.allowedIdsForAgent(eq(ResourceAuthorizationPort.MCP_TOOL),anyString(),anyString(),anySet(),eq(Set.of(tool)),eq("agent"))).thenReturn(Set.of(tool));
        when(grants.allowedIdsForAgent(eq(ResourceAuthorizationPort.KNOWLEDGE),anyString(),anyString(),anySet(),anySet(),eq("agent"))).thenReturn(Set.of("doc-1"));
        when(registry.hasTool(tool)).thenReturn(true);when(registry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder().operationType("read").build());
        when(catalog.registeredTools()).thenReturn(List.of(new McpToolCatalogQueryPort.RegisteredTool(tool,"chatchat-mcp-server","MCP","document_search","documents")));
        when(contract.scope()).thenReturn(scope);when(contract.taskId()).thenReturn("task");when(contract.authorization().allowedTools()).thenReturn(List.of("document_search"));
        var profile=service.snapshot(scope,"agent",false);when(contract.runtimeContext()).thenReturn(Map.of("documentSearch",profile));
    }
    ToolRuntimeExecution result(String doc,String content){
        var data=new LinkedHashMap<String,Object>();data.put("contractVersion","document_evidence_v1");data.put("query","query");
        data.put("results",doc==null?List.of():List.of(Map.of("fileId",doc,"tenantId","tenant","fileName","Guide","chunkId","c-1","content",content)));
        return new ToolRuntimeExecution(ToolOutput.success(data),null,null,"success",Map.of());
    }
    @Test void usesExistingToolRuntimeWithServerOwnedAgentIdentityAndCitations(){
        when(runtime.execute(any())).thenReturn(result("doc-1","Authorized evidence"));
        var response=service.read(contract,"call-1",json.valueToTree(Map.of("query","terms")));
        assertThat(response).containsEntry("status","MATCHES");assertThat(response.toString()).contains("Authorized evidence");
        var request=org.mockito.ArgumentCaptor.forClass(ToolRuntimeRequest.class);verify(runtime).execute(request.capture());
        assertThat(request.getValue().getTenantId()).isEqualTo("tenant");assertThat(request.getValue().getUserId()).isEqualTo("user");
        assertThat(request.getValue().getAttributes()).containsEntry("authorizationAgentId","agent");
        assertThat(request.getValue().getToolInput().getParameters()).doesNotContainKeys("tenantId","roles","agentId");
        assertThat(request.getValue().getToolInput().getParameters()).containsEntry("fileIds",List.of("doc-1"));
    }
    @Test void agentWithoutBoundKnowledgeCannotExposeDocumentTool(){
        when(scopes.resolve("tenant","user","agent",List.of(),List.of())).thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(),List.of(),List.of(),true,true));
        assertThat(service.snapshot(scope,"agent",false)).isEmpty();verifyNoInteractions(runtime);
    }
    @Test void spoofedIdentityAndForeignFileSelectionAreDeniedBeforeSearch(){
        for(String field:List.of("tenantId","userId","agentId","roles","selectedDocumentIds","index"))
            assertThatThrownBy(()->service.read(contract,"call",json.valueToTree(Map.of("query","q",field,"spoof")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.read(contract,"call",json.valueToTree(Map.of("query","q","fileIds",List.of("other-doc"))))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(runtime);
    }
    @Test void foreignEvidenceAndRevocationDuringIoCannotReachTheModel(){
        when(runtime.execute(any())).thenReturn(result("other-doc","SECRET"));
        assertThatThrownBy(()->service.read(contract,"call",json.valueToTree(Map.of("query","q")))).isInstanceOf(SecurityException.class);
        when(runtime.execute(any())).thenAnswer(call->{when(scopes.resolve("tenant","user","agent",List.of(),List.of())).thenReturn(SkillExecutionScopePort.EffectiveScope.denied(List.of()));return result("doc-1","REVOKED");});
        assertThatThrownBy(()->service.read(contract,"call-2",json.valueToTree(Map.of("query","q")))).isInstanceOf(SecurityException.class);
    }
    @Test void emptyMatchesAndSearchFailuresRemainDifferentOutcomes(){
        when(runtime.execute(any())).thenReturn(result(null,""));assertThat(service.read(contract,"empty",json.valueToTree(Map.of("query","q")))).containsEntry("status","NO_MATCHES");
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.failure("unavailable"),null,null,"failed",Map.of()));
        assertThat(service.read(contract,"failed",json.valueToTree(Map.of("query","q")))).containsEntry("status","ERROR");
    }
    @Test void documentRetrievalDoesNotNeedInternetAuthorization(){
        assertThat(service.snapshot(scope,"agent",false)).containsEntry("agentId","agent");
        verify(resources,atLeastOnce()).resolve("tenant","user","agent",false);
        verify(resources,never()).resolve(anyString(),anyString(),anyString(),eq(true));
    }
    @Test void signedReadCapabilityUsesExistingArgumentBoundConfirmation(){
        when(runtime.checkRecoveryAdmission(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.failure("confirmation"),null,null,"confirmation_required",Map.of("confirmation",Map.of("token","bound-token"))));
        when(runtime.execute(any())).thenReturn(result("doc-1","Authorized evidence"));
        assertThat(service.read(contract,"confirmed",json.valueToTree(Map.of("query","q")))).containsEntry("status","MATCHES");
        var request=org.mockito.ArgumentCaptor.forClass(ToolRuntimeRequest.class);verify(runtime).execute(request.capture());
        assertThat(request.getValue().getAttributes()).containsEntry("mcpConfirmation",Map.of("approved",true,"token","bound-token"));
    }
    @Test void taskApprovalCannotOverrideRuntimeDeny(){
        when(runtime.checkRecoveryAdmission(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.failure("policy denied"),null,null,"denied",Map.of()));
        assertThat(service.read(contract,"denied",json.valueToTree(Map.of("query","q")))).containsEntry("status","ERROR");
        verify(runtime,never()).execute(any());
    }
    @Test void registryWarmupCannotSilentlyRemoveAuthorizedDocumentCapability(){
        when(catalog.registeredTools()).thenReturn(List.of());
        assertThatThrownBy(()->service.snapshot(scope,"agent",false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("503");
        verifyNoInteractions(runtime);
    }
    @Test void existingMcpAnalysisEnvelopeUsesGovernedDataAndNeverRawFallback(){
        var envelope=Map.of("schemaVersion","mcp_analysis_payload.v1","data",result("doc-1","Authorized").output().getData(),"rawData",result("other-doc","SECRET").output().getData());
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.success(envelope),null,null,"success",Map.of()));
        assertThat(service.read(contract,"wrapped",json.valueToTree(Map.of("query","q"))).toString()).contains("Authorized").doesNotContain("SECRET");
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.success(Map.of("schemaVersion","mcp_analysis_payload.v1","data",Map.of(),"rawData",result("doc-1","Raw").output().getData())),null,null,"success",Map.of()));
        assertThatThrownBy(()->service.read(contract,"raw",json.valueToTree(Map.of("query","q")))).isInstanceOf(IllegalStateException.class);
    }
    @Test void tagScopeIsAppliedByTheServerBeforeRetrieval(){
        when(scopes.resolve("tenant","user","agent",List.of(),List.of())).thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(),List.of("authorized-tag"),List.of("reader"),true,true));
        when(runtime.execute(any())).thenReturn(result("doc-1","Authorized"));
        service.read(contract,"tags",json.valueToTree(Map.of("query","q")));
        var request=org.mockito.ArgumentCaptor.forClass(ToolRuntimeRequest.class);verify(runtime).execute(request.capture());
        assertThat(request.getValue().getToolInput().getParameters()).containsEntry("filters",Map.of("tags",List.of("authorized-tag")));
    }
}
