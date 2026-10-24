package com.chatchat.api.service;

import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublishedMcpToolCatalogTest {
    final McpControlPlaneClient client=mock(McpControlPlaneClient.class);
    final McpToolCatalogQueryPort catalog=mock(McpToolCatalogQueryPort.class);
    final ResourceAuthorizationPort authorization=mock(ResourceAuthorizationPort.class);
    final ObjectMapper json=new ObjectMapper();
    final PublishedMcpToolCatalog service=new PublishedMcpToolCatalog(client,catalog,authorization,json);
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final String name="mcp_chatchat_mcp_server_sales_template_query";
    Map<String,Object> row(String state,String kind,String role,String category) throws Exception {
        return Map.of("state",state,"kind",kind,"title","业务查询","description","查询已选范围", "definitionJson",json.writeValueAsString(Map.of("domainCode","sales","roleId",role,"businessCategory",category,"templateKeys",List.of("a","b"))));
    }
    @BeforeEach void setup() throws Exception {
        var user=mock(EnterpriseAdminService.UserView.class);
        when(user.id()).thenReturn("user");when(user.tenantId()).thenReturn("tenant");when(user.roleIds()).thenReturn(List.of("role"));when(user.permissionCodes()).thenReturn(List.of());
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW,user);
        when(client.request(anyString(),anyString(),isNull())).thenReturn(json.valueToTree(List.of(row("RUNNING","CONTROLLED_TEMPLATE","role","客户服务"))));
        when(authorization.allowedIds(anyString(),anyString(),anyString(),anySet(),anySet())).thenAnswer(c->c.getArgument(4));
        when(catalog.registeredTools()).thenReturn(List.of(new McpToolCatalogQueryPort.RegisteredTool(name,"chatchat-mcp-server","MCP","sales_template_query","technical instructions"),new McpToolCatalogQueryPort.RegisteredTool("raw_execute","s","MCP","raw_execute","internal")));
    }
    @Test void templateDetailsUseAuthenticatedIdentityAndRejectHiddenToolsBeforeForwarding() {
        service.templates(request, name);
        verify(client).request("GET", "/api/v1/tool-publications/templates?tenantId=tenant&actorId=user&domainCode=sales", null);
        clearInvocations(client);
        assertThrows(SecurityException.class, () -> service.templates(request, "raw_execute"));
        verify(client, never()).request(eq("GET"), contains("/templates?"), isNull());
    }
    @Test void registrationAloneDoesNotMakeAnAgentSelectableTool() {
        var tools=service.registeredTools(request);assertEquals(1,tools.size());assertEquals(name,tools.get(0).localToolName());
        assertEquals("客户服务",tools.get(0).category());assertEquals("业务查询",tools.get(0).chineseAlias());assertEquals("查询已选范围",tools.get(0).description());assertEquals(2,tools.get(0).applicability().get("templateCount"));
    }
    @Test void rejectsDraftOfflineAndAnotherRoleEvenWhenRegistered() throws Exception {
        for(var value:List.of(row("DRAFT","CONTROLLED_TEMPLATE","role","业务"),row("OFFLINE","CONTROLLED_TEMPLATE","role","业务"),row("RUNNING","CONTROLLED_TEMPLATE","other-role","业务"),row("RUNNING","TEMPLATE_CAPABILITY","role","业务"))) {
            when(client.request(anyString(),anyString(),isNull())).thenReturn(json.valueToTree(List.of(value)));assertTrue(service.registeredTools(request).isEmpty());
        }
    }
    @Test void resourceRevocationIsRecheckedInsteadOfUsingAnUnfilteredCache() {
        assertEquals(1,service.registeredTools(request).size());when(authorization.allowedIds(anyString(),anyString(),anyString(),anySet(),anySet())).thenReturn(Set.of());assertTrue(service.registeredTools(request).isEmpty());
    }
    @Test void absentCategoryIsExplicitlyPendingRatherThanAnExecutorType() throws Exception {
        when(client.request(anyString(),anyString(),isNull())).thenReturn(json.valueToTree(List.of(row("RUNNING","CONTROLLED_TEMPLATE","role",""))));assertEquals("未分类",service.registeredTools(request).get(0).category());
    }
    @Test void currentCategoryOverridesImmutableSnapshotIncludingExplicitUnclassification() throws Exception {
        var value=new HashMap<>(row("RUNNING","CONTROLLED_TEMPLATE","role","旧分类"));value.put("businessCategory","新分类");
        when(client.request(anyString(),anyString(),isNull())).thenReturn(json.valueToTree(List.of(value)));
        assertEquals("新分类",service.registeredTools(request).get(0).category());
        value.put("businessCategory","");when(client.request(anyString(),anyString(),isNull())).thenReturn(json.valueToTree(List.of(value)));
        assertEquals("未分类",service.registeredTools(request).get(0).category());
    }
    @Test void unavailableControlPlaneNeverFallsBackToAllRegisteredTools() {
        when(client.request(anyString(),anyString(),isNull())).thenThrow(new IllegalStateException("offline"));assertThrows(IllegalStateException.class,()->service.registeredTools(request));assertTrue(service.registeredTools(null).isEmpty());
    }
    @Test void systemSearchPreservesAgentSelectionAndRechecksGrantsWithoutExposingRawTools() {
        String document = "mcp_chatchat_mcp_server_document_search";
        String web = "mcp_chatchat_mcp_server_web_search";
        when(catalog.registeredTools()).thenReturn(List.of(
            new McpToolCatalogQueryPort.RegisteredTool(document, "chatchat-mcp-server", "MCP", "document_search", "document"),
            new McpToolCatalogQueryPort.RegisteredTool(web, "chatchat-mcp-server", "MCP", "web_search", "web"),
            new McpToolCatalogQueryPort.RegisteredTool("raw_execute", "chatchat-mcp-server", "MCP", "raw_execute", "raw"),
            new McpToolCatalogQueryPort.RegisteredTool("mcp_foreign_document_search", "foreign", "MCP", "document_search", "foreign")));
        var publications = service.publications(request);
        assertTrue(publications.get(document).systemTool());
        assertTrue(publications.get(web).systemTool());
        assertFalse(publications.get(name).systemTool());
        var tools = service.registeredTools(request);
        assertEquals(List.of(document, web), tools.stream().map(McpToolCatalogQueryPort.RegisteredTool::localToolName).toList());
        assertEquals(true, tools.get(0).applicability().get("systemTool"));
        assertThrows(SecurityException.class, () -> service.templates(request, document));
        when(authorization.allowedIds(anyString(), anyString(), anyString(), anySet(), anySet())).thenReturn(Set.of());
        assertTrue(service.publications(request).isEmpty());
        assertTrue(service.publications(null).isEmpty());
    }
}
