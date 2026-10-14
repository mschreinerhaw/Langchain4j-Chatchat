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
    private McpToolDescriptor tool() {
        return new McpToolDescriptor("registered", "local-schema", "sql_schema_context_query", "", "metadata",
            Map.of("properties", Map.of("contractVersion", Map.of("enum", List.of(SqlStudioMetadataGateway.CONTRACT)))), Map.of(), Map.of(), Map.of());
    }
}
