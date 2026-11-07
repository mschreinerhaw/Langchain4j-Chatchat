package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.service.*;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Governance components only; this fixture is not a real MCP/model acceptance run. */
class NativeCodingMcpServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    final McpRuntimeTransportPort directory=mock(McpRuntimeTransportPort.class);
    final ResourceAuthorizationPort grants=mock(ResourceAuthorizationPort.class);
    final DurableCodingTaskService tasks=mock(DurableCodingTaskService.class);
    final NativeCodingWorkspaceService workspace=mock(NativeCodingWorkspaceService.class);
    final CodingTaskContract contract=mock(CodingTaskContract.class,RETURNS_DEEP_STUBS);
    final CodingJournal.Lease lease=mock(CodingJournal.Lease.class);
    final Map<String,JsonNode> state=new HashMap<>();
    final Map<String,Object> schema=Map.of("type","object","properties",Map.of("value",Map.of("type","integer")),"required",List.of("value"));
    NativeCodingMcpService service;
    McpToolDescriptor descriptor(String name,Map<String,Object> input){return new McpToolDescriptor("server",name,"remote_"+name,"A real schema","external",input,Map.of(),Map.of(),Map.of());}
    @BeforeEach void setup(){
        service=new NativeCodingMcpService(directory,grants,tasks,workspace,json);
        when(directory.services()).thenReturn(List.of(new McpServiceDescriptor("server","Server","existing","http",true,Map.of())));
        when(directory.tools(any())).thenReturn(List.of(descriptor("allowed",schema),descriptor("denied",schema)));
        when(directory.findTool("server","allowed")).thenReturn(Optional.of(descriptor("allowed",schema)));
        when(grants.allowedIdsForAgent(anyString(),eq("tenant"),eq("owner"),anySet(),eq(Set.of("allowed")),eq("agent"))).thenReturn(Set.of("allowed"));
        when(grants.allowedIdsForAgent(anyString(),eq("tenant"),eq("owner"),anySet(),eq(Set.of("denied")),eq("agent"))).thenReturn(Set.of());
        when(contract.scope()).thenReturn(scope);when(contract.taskId()).thenReturn("task");
        when(contract.authorization().allowedTools()).thenReturn(List.of("allowed"));
        when(contract.budget().deadlineAt()).thenReturn(Instant.now().plusSeconds(60));
        var frozen=service.catalog(scope,List.of(new NativeCodingMcpService.Binding("server","agent")));
        when(contract.runtimeContext()).thenReturn(Map.of(NativeCodingMcpService.CONTEXT,frozen));
        when(workspace.ioLock(contract)).thenReturn(new Object());
        when(workspace.intent(contract,"call")).thenReturn(json.valueToTree(Map.of("tool","allowed","inputJson","{\"value\":7}")));
        when(tasks.privateState(eq(scope),eq("task"),anyString())).thenAnswer(i->state.get(i.getArgument(2)));
        doAnswer(i->{String key=i.getArgument(3);JsonNode previous=i.getArgument(4);assertThat(state.get(key)).isEqualTo(previous);state.put(key,i.getArgument(5));return null;})
            .when(tasks).privateState(eq(scope),eq("task"),eq(lease),anyString(),any(),any());
    }
    @Test void discoveryFiltersBeforeDisclosureAndKeepsOriginalSchema(){
        var tools=service.list(contract,lease,"server");assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).containsEntry("agentToolName","allowed").containsEntry("inputSchema",schema);
        assertThatThrownBy(()->service.list(contract,lease,"other")).isInstanceOf(SecurityException.class);
    }
    @Test void springSelectsCanonicalTransportWhenSeveralDirectoriesExist(){
        var local=mock(McpServiceDirectory.class);var kernel=mock(McpRuntimeTransportPort.class);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withPropertyValues("chatchat.coding.native.enabled=true")
            .withBean("mcpRuntimeTransportPort",McpRuntimeTransportPort.class,()->directory)
            .withBean("dynamicMcpServiceDirectory",McpServiceDirectory.class,()->local)
            .withBean("grpcBackedMcpRuntimeKernel",McpRuntimeTransportPort.class,()->kernel)
            .withBean(ResourceAuthorizationPort.class,()->grants)
            .withBean(DurableCodingTaskService.class,()->tasks)
            .withBean(NativeCodingWorkspaceService.class,()->workspace)
            .withBean(ObjectMapper.class,()->json)
            .withUserConfiguration(NativeCodingMcpService.class)
            .run(context->{
                assertThat(context).hasNotFailed().hasSingleBean(NativeCodingMcpService.class);
                assertThat(context.getBean(NativeCodingMcpService.class).catalog(scope,List.of(new NativeCodingMcpService.Binding("server","agent")))).hasSize(1);
                verifyNoInteractions(local,kernel);
            });
    }
    @Test void revocationAndSchemaDriftRefuseExistingTask(){
        when(grants.allowedIdsForAgent(anyString(),anyString(),anyString(),anySet(),anySet(),anyString())).thenReturn(Set.of());
        assertThatThrownBy(()->service.requireLive(contract,"allowed")).isInstanceOf(SecurityException.class).hasMessageContaining("revoked");
        assertThat(service.list(contract,lease,"server")).isEmpty();
        when(grants.allowedIdsForAgent(anyString(),anyString(),anyString(),anySet(),anySet(),anyString())).thenReturn(Set.of("allowed"));
        when(directory.findTool("server","allowed")).thenReturn(Optional.of(descriptor("allowed",Map.of("type","array"))));
        assertThatThrownBy(()->service.requireLive(contract,"allowed")).isInstanceOf(SecurityException.class).hasMessageContaining("Schema changed");
        verify(directory,never()).invoke(any());
    }
    @Test void confirmedResultReplaysAndCallerContextComesFromContract(){
        var raw=Map.of("content",List.of(Map.of("type","text","text","seven")),"structuredContent",Map.of("value",7));
        when(directory.invoke(any())).thenAnswer(i->{McpServiceCall call=i.getArgument(0);
            assertThat(call.arguments()).isEqualTo(Map.of("value",7));assertThat(call.context()).containsEntry("tenantId","tenant").containsEntry("authorizationAgentId","agent");
            return new McpServiceResult(null,call.requestId(),"server","allowed",McpServiceResultStatus.SUCCESS,null,raw,null,null,false,null,Map.of(),0);});
        assertThat(service.call(contract,lease,"server","remote_allowed","call",Map.of("value",7))).isEqualTo(raw);
        assertThat(service.call(contract,lease,"server","remote_allowed","call",Map.of("value",7))).isEqualTo(raw);
        verify(directory,times(1)).invoke(any());
    }
    @Test void lostResponseCannotRedispatch(){
        when(directory.invoke(any())).thenThrow(new IllegalStateException("Connection lost after dispatch"));
        assertThatThrownBy(()->service.call(contract,lease,"server","remote_allowed","call",Map.of("value",7))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.call(contract,lease,"server","remote_allowed","call",Map.of("value",7))).hasMessageContaining("MCP_RESULT_UNKNOWN");
        verify(directory,times(1)).invoke(any());
    }
    @Test void forgedParametersAndForeignTenantCannotReachTransport(){
        assertThatThrownBy(()->service.call(contract,lease,"server","remote_allowed","call",Map.of("value",8))).isInstanceOf(SecurityException.class);
        when(contract.scope()).thenReturn(new KernelDataScope("other","owner",null,null,"run",null,Map.of()));
        assertThatThrownBy(()->service.call(contract,lease,"server","remote_allowed","call",Map.of("value",7))).isInstanceOf(SecurityException.class);
        verify(directory,never()).invoke(any());
    }
}
