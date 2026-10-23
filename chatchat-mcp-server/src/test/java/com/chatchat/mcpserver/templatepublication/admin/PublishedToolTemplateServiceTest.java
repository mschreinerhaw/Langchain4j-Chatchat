package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.catalog.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublishedToolTemplateServiceTest {
    final TemplateCallerScope callers = mock(TemplateCallerScope.class);
    final ToolReleaseRepository releases = mock(ToolReleaseRepository.class);
    final TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
    final TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
    final ResourceAuthorizationPort authorization = mock(ResourceAuthorizationPort.class);
    final PublishedToolTemplateService service = new PublishedToolTemplateService(callers, releases, bindings, catalog, authorization, new ObjectMapper());
    ToolRelease release;
    TemplateAssetCatalogService.BoundTemplate template(String key) {
        return new TemplateAssetCatalogService.BoundTemplate(key + "@asset", key, key, "业务用途", "secret host", "分类", Map.of(), "revision", "executor", Map.of("password", "secret"));
    }
    @BeforeEach void setup() {
        when(callers.open("tenant", "user")).thenAnswer(call -> McpInvocationContext.open(new McpInvocationContext.Context(null,null,null,null,null,"user","name","tenant","role",null,null,null,null,null,null,null)));
        when(authorization.allowedIds(anyString(), anyString(), anyString(), anySet(), anySet())).thenAnswer(call -> call.getArgument(4));
        release = new ToolRelease(); release.setTenantId("tenant"); release.setState("RUNNING"); release.setKind("CONTROLLED_TEMPLATE");
        release.setDefinitionJson("{\"domainCode\":\"sales\",\"templateKeys\":[\"sql_datasource:a\",\"sql_datasource:b\",\"sql_datasource:c\"]}");
        when(releases.findByTenantIdOrderByCreatedAtDesc("tenant")).thenReturn(List.of(release));
        when(bindings.allowedTemplates(any(), eq("sales_template_query"))).thenReturn(Map.of("sql_datasource", Set.of("a","b","unselected")));
        when(catalog.businessTemplates(false)).thenReturn(List.of(template("sql_datasource:a"), template("sql_datasource:a"), template("sql_datasource:c"), template("sql_datasource:unselected")));
        when(catalog.readableContent("sql_datasource:a")).thenReturn("select 1");
    }
    @Test void intersectsPublishedSelectionBindingAndCurrentTemplateAssetPermissionsAndDeduplicates() throws Exception {
        var result = service.templates("tenant", "user", "sales");
        assertEquals(1, result.size()); assertEquals("select 1", result.get(0).content());
        String payload = new ObjectMapper().writeValueAsString(result);
        assertFalse(payload.contains("secret")); assertFalse(payload.contains("inputSchema")); assertFalse(payload.contains("executor"));
        assertNull(McpInvocationContext.current());
    }
    @Test void reevaluatesRevocationOnEveryRead() {
        assertEquals(1, service.templates("tenant", "user", "sales").size());
        when(catalog.businessTemplates(false)).thenReturn(List.of());
        assertTrue(service.templates("tenant", "user", "sales").isEmpty());
        when(authorization.allowedIds(anyString(), anyString(), anyString(), anySet(), anySet())).thenReturn(Set.of());
        assertThrows(SecurityException.class, () -> service.templates("tenant", "user", "sales"));
    }
    @Test void rejectsOfflineDraftAnotherTenantAndWrongKind() {
        for (String state : List.of("DRAFT", "OFFLINE")) {
            release.setState(state); assertThrows(SecurityException.class, () -> service.templates("tenant", "user", "sales"));
        }
        release.setState("RUNNING"); release.setTenantId("other");
        assertThrows(SecurityException.class, () -> service.templates("tenant", "user", "sales"));
        release.setTenantId("tenant"); release.setKind("TEMPLATE_CAPABILITY");
        assertThrows(SecurityException.class, () -> service.templates("tenant", "user", "sales"));
        verify(catalog, never()).businessTemplates(anyBoolean());
    }
    @Test void failsClosedWhenCallerCannotBeRefreshedAndRejectsMalformedDomain() {
        doThrow(new SecurityException("unavailable")).when(callers).open("tenant", "user");
        assertThrows(SecurityException.class, () -> service.templates("tenant", "user", "sales"));
        assertThrows(IllegalArgumentException.class, () -> service.templates("tenant", "user", "../sales"));
        verifyNoInteractions(catalog);
    }
}
