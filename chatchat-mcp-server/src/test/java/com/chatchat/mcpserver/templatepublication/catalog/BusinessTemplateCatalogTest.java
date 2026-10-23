package com.chatchat.mcpserver.templatepublication.catalog;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.mcpserver.api.registry.*;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;
import com.chatchat.mcpserver.python.PythonTemplateCatalog;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTemplateCatalogTest {
    @Test void templateGrantAndAssetOperationGrantAreIndependentAndSnapshotTracksConfiguration() {
        var apis = mock(ApiServiceConfigService.class); var authority = mock(McpAuthorizationService.class);
        var grants = mock(ResourceAuthorizationPort.class);
        var service = new TemplateAssetCatalogService(mock(CommandTemplateService.class), mock(SqlTemplateService.class),
            mock(HttpEndpointConfigService.class), mock(DatabaseQueryConfigService.class), apis, mock(PythonTemplateCatalog.class),
            mock(BusinessCategoryService.class), mock(SshHostConfigService.class), mock(SqlDatasourceConfigService.class), authority,
            new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(service, "templateAuthorization", grants);
        when(authority.currentCallerContext()).thenReturn(new McpAuthorizationService.CallerAuthorizationContext("tenant", "user", "user", List.of()));
        var api = new ApiServiceConfig(); api.setId("asset"); api.setToolName("api_customer"); api.setTitle("客户查询"); api.setMethod("GET"); api.setUrlTemplate("https://example.invalid/customer");
        api.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"usageCount\":{\"type\":\"integer\"}}}");
        when(apis.listEnabled()).thenReturn(List.of(api));
        assertTrue(service.businessTemplates(false).isEmpty());
        when(grants.allowedIds(eq("TEMPLATE"), eq("tenant"), eq("user"), anySet(), anySet())).thenReturn(Set.of("api_service:api_customer"));
        assertTrue(service.businessTemplates(false).isEmpty());
        when(authority.callerAllowsOperation("api_customer", "api_service", "asset", "read")).thenReturn(true);
        var first = service.businessTemplates(false).get(0);
        assertEquals("api_template_execute", first.executor()); assertEquals("api_customer", first.binding().get("templateId"));
        assertFalse(first.publicView().containsKey("executor")); assertFalse(first.publicView().toString().contains("example.invalid"));
        ReflectionTestUtils.setField(api, "updatedAt", java.time.Instant.now()); assertEquals(first.revision(), service.businessTemplates(false).get(0).revision());
        api.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"usageCount\":{\"type\":\"string\"}}}");
        assertNotEquals(first.revision(), service.businessTemplates(false).get(0).revision());
        api.setMethod("POST"); assertTrue(service.businessTemplates(false).isEmpty());
        assertEquals(1, service.businessTemplates(true).size());
        when(apis.listEnabled()).thenReturn(List.of()); assertTrue(service.businessTemplates(false).isEmpty());
    }
}
