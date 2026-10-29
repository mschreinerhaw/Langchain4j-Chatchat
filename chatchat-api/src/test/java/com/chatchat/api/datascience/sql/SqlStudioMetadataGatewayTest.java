package com.chatchat.api.datascience.sql;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.service.ApiKernelScopeResolver;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlStudioMetadataGatewayTest {
    McpRuntimeKernel kernel = mock(McpRuntimeKernel.class);
    SqlStudioMetadataGateway gateway = new SqlStudioMetadataGateway(kernel, new ApiKernelScopeResolver());
    @Test void bindsAuthenticatedIdentityAndUsesRegisteredKernelContract() {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant"); request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "owner");
        when(kernel.tools(any())).thenReturn(List.of(tool()));
        when(kernel.execute(any(McpServiceCall.class))).thenAnswer(call -> {
            McpServiceCall input = call.getArgument(0);
            assertThat(input.serviceId()).isEqualTo("registered");
            assertThat(input.context()).containsEntry("tenantId", "tenant").containsEntry("userId", "owner").doesNotContainKey("roles");
            return new McpServiceResult(null, input.requestId(), input.serviceId(), input.toolName(), McpServiceResultStatus.SUCCESS,
                Map.of("structuredContent", Map.of("contractVersion", SqlStudioMetadataGateway.CONTRACT, "doesNotExecuteSql", true, "items", List.of())), null, null, null, false, null, Map.of(), 0);
        });
        var result = gateway.invoke(request, null, Map.of("contractVersion", SqlStudioMetadataGateway.CONTRACT, "operation", "LIST_ASSETS"));
        assertThat(result.serviceId()).isEqualTo("registered");
        assertThat(result.metadata()).containsEntry("doesNotExecuteSql", true);
        verify(kernel, times(1)).execute(any(McpServiceCall.class));
    }
    @Test void refusesMissingOrAmbiguousRegisteredProviderWithoutGuessing() {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant"); request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "owner");
        when(kernel.tools(any())).thenReturn(List.of());
        assertThatThrownBy(() -> gateway.invoke(request, null, Map.of())).isInstanceOf(ResponseStatusException.class);
        when(kernel.tools(any())).thenReturn(List.of(tool(), tool()));
        assertThatThrownBy(() -> gateway.invoke(request, null, Map.of())).isInstanceOf(ResponseStatusException.class);
        verify(kernel, never()).execute(any(McpServiceCall.class));
    }
    @Test void rejectsUnauthenticatedRequestsBeforeDirectoryAndKernelAccess() {
        assertThatThrownBy(() -> gateway.invoke(new MockHttpServletRequest(), null, Map.of())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(kernel);
    }
    @Test void providerDirectoryProjectsOnlyEnabledV2ServicesWithoutConnectionsOrExecution() {
        when(kernel.tools(any())).thenReturn(List.of(tool()));
        when(kernel.services()).thenReturn(List.of(
            new McpServiceDescriptor("registered","Metadata","provider","http",true,Map.of("internal","not-public")),
            new McpServiceDescriptor("unrelated","Other","provider","http",true,Map.of())));
        assertThat(gateway.providers()).containsExactly(new SqlStudioMetadataGateway.Provider("registered","Metadata"));
        verify(kernel,never()).execute(any(McpServiceCall.class));
    }
    @Test void backgroundScopeKeepsAuthorityAndTaskDeadlineWithoutInjectingConversationIntoMetadataArguments() {
        when(kernel.tools(any())).thenReturn(List.of(tool()));
        long deadline = System.currentTimeMillis()+1000;
        when(kernel.execute(any(McpServiceCall.class))).thenAnswer(invocation -> {
            McpServiceCall call=invocation.getArgument(0);
            assertThat(call.context()).containsEntry("tenantId","tenant").containsEntry("userId","owner").containsEntry("runId","run").doesNotContainKey("conversationId");
            assertThat(call.arguments()).containsOnlyKeys("operation");assertThat(call.deadlineAt()).isEqualTo(deadline);
            return new McpServiceResult(null,call.requestId(),call.serviceId(),call.toolName(),McpServiceResultStatus.SUCCESS,
                Map.of("contractVersion",SqlStudioMetadataGateway.CONTRACT,"doesNotExecuteSql",true),null,null,null,false,null,Map.of(),0);
        });
        gateway.invokeScoped(new com.chatchat.common.kernel.KernelDataScope("tenant","owner","request","conversation","run",null,Map.of("deadlineAt",deadline)),"registered",Map.of("operation","LIST_ASSETS"));
    }
    @Test void distinguishesMetadataTransportDeadlineFromAnExpiredWholeTask(){
        when(kernel.tools(any())).thenReturn(List.of(tool()));
        when(kernel.execute(any(McpServiceCall.class))).thenAnswer(invocation->{McpServiceCall call=invocation.getArgument(0);return new McpServiceResult(null,call.requestId(),call.serviceId(),call.toolName(),McpServiceResultStatus.REJECTED,null,null,"MCP_CALL_DEADLINE_EXCEEDED","Expired",false,null,Map.of(),0);});
        var live=new com.chatchat.common.kernel.KernelDataScope("tenant","owner","request",null,"run",null,Map.of("deadlineAt",System.currentTimeMillis()+60000));
        assertThatThrownBy(()->gateway.invokeScoped(live,"registered",Map.of())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("METADATA_TOOL_TIMEOUT");
        var expired=new com.chatchat.common.kernel.KernelDataScope("tenant","owner","request",null,"run",null,Map.of("deadlineAt",System.currentTimeMillis()-1));
        assertThatThrownBy(()->gateway.invokeScoped(expired,"registered",Map.of())).isInstanceOf(com.chatchat.agents.orchestration.model.AgentDeadlineExceededException.class);
    }
    private McpToolDescriptor tool() {
        return new McpToolDescriptor("registered", "local-schema", "sql_schema_context_query", "", "metadata",
            Map.of("properties", Map.of("contractVersion", Map.of("enum", List.of(SqlStudioMetadataGateway.CONTRACT)))), Map.of(), Map.of(), Map.of());
    }
    @Test void businessRetrievalBudgetIsRepairableAndDistinctFromServiceOutage(){
        when(kernel.tools(any())).thenReturn(List.of(new McpToolDescriptor("registered","local-business","enterprise_metadata_search","","metadata",Map.of(),Map.of(),Map.of(),Map.of())));
        when(kernel.execute(any(McpServiceCall.class))).thenReturn(new McpServiceResult(null,"request","registered","enterprise_metadata_search",McpServiceResultStatus.FAILED,
            Map.of("error","Too many requests in one retrieval"),null,"MCP_TOOL_FAILED","retrieval failed",false,null,Map.of(),0));
        var scope=new com.chatchat.common.kernel.KernelDataScope("tenant","owner","request",null,"run",null,Map.of());
        var error=catchThrowableOfType(()->gateway.referenceScoped(scope,"registered","enterprise_metadata_search",Map.of("query","assets")),ResponseStatusException.class);
        assertThat(SqlWorkspaceMetadataTools.failure(error)).containsEntry("category","QUERY_BUDGET").containsEntry("retryable",true);
    }
}
