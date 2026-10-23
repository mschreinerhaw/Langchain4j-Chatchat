package com.chatchat.mcpserver.mcp;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.authorization.McpAuthorizationProperties;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.license.McpLicenseService;
import com.chatchat.license.LicenseStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpInvocationLoggingFilterTest {
    @Test void signedTemplateIdentityControlsDiscoveryAndCallAndCannotBeReplacedByHeaders() throws Exception {
        var json = new ObjectMapper();
        var capabilities = mock(com.chatchat.mcpserver.templatepublication.admin.TemplateCapabilityService.class);
        var publisher = mock(com.chatchat.mcpserver.templatepublication.publisher.TemplateCapabilityPublisher.class);
        var credentials = mock(com.chatchat.common.security.InternalCredentialProperties.class);
        when(credentials.resolvedSecret()).thenReturn("secret");
        var row = new com.chatchat.mcpserver.templatepublication.admin.ToolRelease(); row.setLogicalId("01234567-89ab-cdef-0123-456789abcdef");
        String name = com.chatchat.mcpserver.templatepublication.admin.TemplateCapabilityService.toolName(row);
        when(capabilities.running()).thenReturn(java.util.List.of(row)); when(capabilities.available(eq(row), anyMap())).thenReturn(true);
        var tool = io.modelcontextprotocol.spec.McpSchema.Tool.builder().name(name).title("客户查询").description("查询")
            .inputSchema(java.util.Map.of("type", "object", "properties", java.util.Map.of())).build();
        when(publisher.contribute()).thenReturn(java.util.List.of(com.chatchat.mcpserver.tool.ToolPublication.from(
            io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder().build()).build())));
        var filter = new McpInvocationLoggingFilter(mock(InvocationAuditService.class), json, new McpAuthorizationProperties(),
            mock(McpAuthorizationService.class), mock(McpLicenseService.class), mock(McpServiceRegistryService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "templateCapabilities", capabilities);
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "templatePublisher", publisher);
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "templateCredentials", credentials);
        var proof = com.chatchat.common.security.McpCallerSignature.issue("secret", "tenant", "user", "agent");
        var meta = java.util.Map.of(com.chatchat.common.security.McpCallerSignature.KEY, proof);
        var discovery = new MockHttpServletRequest("POST", "/mcp");
        discovery.setContent(json.writeValueAsBytes(java.util.Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", java.util.Map.of("_meta", meta))));
        var response = new MockHttpServletResponse(); filter.doFilterInternal(discovery, response, mock(FilterChain.class));
        assertThat(json.readTree(response.getContentAsByteArray()).path("result").path("tools")).hasSize(1);
        var request = new MockHttpServletRequest("POST", "/mcp"); request.addHeader("X-Tenant-Id", "forged"); request.addHeader("X-User-Id", "forged");
        request.setContent(json.writeValueAsBytes(java.util.Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", java.util.Map.of("name", name, "arguments", java.util.Map.of(), "_meta", meta))));
        FilterChain chain = (req, res) -> { assertThat(McpInvocationContext.current().tenantId()).isEqualTo("tenant"); assertThat(McpInvocationContext.current().userId()).isEqualTo("user"); };
        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        when(capabilities.available(eq(row), anyMap())).thenReturn(false);
        request.setContent(json.writeValueAsBytes(java.util.Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", java.util.Map.of("name", name, "arguments", java.util.Map.of(), "_meta", meta))));
        var denied = new MockHttpServletResponse(); filter.doFilterInternal(request, denied, mock(FilterChain.class)); assertThat(denied.getStatus()).isEqualTo(403);
        discovery.setContent(json.writeValueAsBytes(java.util.Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", java.util.Map.of("_meta", meta))));
        var empty = new MockHttpServletResponse(); filter.doFilterInternal(discovery, empty, mock(FilterChain.class));
        assertThat(json.readTree(empty.getContentAsByteArray()).path("result").path("tools")).isEmpty();
    }

    @Test
    void restoresCallerContextFromStandardMcpRequestMeta() throws Exception {
        InvocationAuditService auditService = mock(InvocationAuditService.class);
        McpLicenseService licenseService = mock(McpLicenseService.class);
        McpAuthorizationService authorizationService = mock(McpAuthorizationService.class);
        when(licenseService.toolDenialReason("python_analysis_query")).thenReturn(null);
        when(authorizationService.authorize(eq("python_analysis_query"), anyMap()))
            .thenReturn(McpAuthorizationService.AuthorizationDecision.allowDecision());
        McpInvocationLoggingFilter filter = new McpInvocationLoggingFilter(
            auditService,
            new ObjectMapper(),
            new McpAuthorizationProperties(),
            authorizationService,
            licenseService,
            mock(McpServiceRegistryService.class)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.setContent("""
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
              "name":"python_analysis_query",
              "arguments":{"query":"分析持仓数据"},
              "_meta":{
                "traceId":"request-1",
                "tenant":{"tenantId":"tenant-1"},
                "user":{"userId":"user-1","username":"analyst","roles":"role-a"}
              }
            }}
            """.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        McpInvocationContext.Context[] captured = new McpInvocationContext.Context[1];
        doAnswer(invocation -> {
            captured[0] = McpInvocationContext.current();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(request, response, chain);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].tenantId()).isEqualTo("tenant-1");
        assertThat(captured[0].userId()).isEqualTo("user-1");
        assertThat(captured[0].username()).isEqualTo("analyst");
        assertThat(captured[0].roles()).isEqualTo("role-a");
        assertThat(captured[0].traceId()).isEqualTo("request-1");
    }

    @Test
    void rejectsMcpInitializationWhenLicenseIsExpired() throws Exception {
        InvocationAuditService auditService = mock(InvocationAuditService.class);
        McpAuthorizationService authorizationService = mock(McpAuthorizationService.class);
        McpLicenseService licenseService = mock(McpLicenseService.class);
        McpAuthorizationProperties properties = new McpAuthorizationProperties();
        when(licenseService.status()).thenReturn(
            LicenseStatus.invalid("EXPIRED", "License 已过期", "SERVER-TEST", null)
        );
        when(licenseService.runtimeDenialReason()).thenReturn("License 已过期");
        McpInvocationLoggingFilter filter = new McpInvocationLoggingFilter(
            auditService, new ObjectMapper(), properties, authorizationService, licenseService,
            mock(McpServiceRegistryService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.setContent("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
            .contains("MCP_LICENSE_EXPIRED", "\"licenseStatus\":\"EXPIRED\"", "License 已过期");
        verifyNoInteractions(authorizationService, chain);
    }

    @Test
    void blocksToolBeforeRoleAuthorizationWhenLicenseDoesNotIncludeFeature() throws Exception {
        InvocationAuditService auditService = mock(InvocationAuditService.class);
        McpAuthorizationService authorizationService = mock(McpAuthorizationService.class);
        McpLicenseService licenseService = mock(McpLicenseService.class);
        McpAuthorizationProperties properties = new McpAuthorizationProperties();
        properties.setEnabled(true);
        when(licenseService.toolDenialReason("database_query"))
            .thenReturn("License 已过期，新的 MCP 工具调用已停止");
        when(licenseService.status()).thenReturn(
            LicenseStatus.invalid("EXPIRED", "License 已过期", "SERVER-TEST", null)
        );
        when(licenseService.runtimeDenialReason()).thenReturn("License 已过期");
        McpInvocationLoggingFilter filter = new McpInvocationLoggingFilter(
            auditService, new ObjectMapper(), properties, authorizationService, licenseService,
            mock(McpServiceRegistryService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-Tenant-Id", "tenant-1");
        request.addHeader("X-User-Id", "user-1");
        request.setContentType("application/json");
        request.setContent("""
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"database_query","arguments":{}}}
            """.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
            .contains("MCP_LICENSE_EXPIRED", "\"licenseStatus\":\"EXPIRED\"", "License 已过期",
                "\"retryable\":false", "\"action\":\"STOP\"");
        verifyNoInteractions(authorizationService, chain);
    }

    @Test
    void blocksEveryToolsCallBeforeDirectPublisherHandlerWhenAuthorizationDenies() throws Exception {
        InvocationAuditService auditService = mock(InvocationAuditService.class);
        McpAuthorizationService authorizationService = mock(McpAuthorizationService.class);
        McpLicenseService licenseService = mock(McpLicenseService.class);
        McpAuthorizationProperties properties = new McpAuthorizationProperties();
        properties.setEnabled(true);
        properties.setRequireTenantContext(true);
        when(authorizationService.authorize(eq("ssh_finance"), anyMap()))
            .thenReturn(McpAuthorizationService.AuthorizationDecision.denyDecision("asset not assigned"));
        McpInvocationLoggingFilter filter = new McpInvocationLoggingFilter(
            auditService,
            new ObjectMapper(),
            properties,
            authorizationService,
            licenseService,
            mock(McpServiceRegistryService.class)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-Tenant-Id", "tenant-1");
        request.addHeader("X-User-Id", "user-1");
        request.setContentType("application/json");
        request.setContent("""
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ssh_finance","arguments":{}}}
            """.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("MCP_ASSET_ACCESS_DENIED");
        verify(authorizationService).authorize(eq("ssh_finance"), anyMap());
        verifyNoInteractions(chain);
    }
}
