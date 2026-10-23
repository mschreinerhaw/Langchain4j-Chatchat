package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.McpCallerSignature;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateCallerScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateCapabilityServiceTest {
    ToolReleaseRepository repository = mock(ToolReleaseRepository.class);
    TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
    TemplateCallerScope callers = mock(TemplateCallerScope.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    ResourceAuthorizationPort permissions = mock(ResourceAuthorizationPort.class);
    InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    List<ToolRelease> rows = new ArrayList<>();
    TemplateCapabilityService service;
    TemplateAssetCatalogService.BoundTemplate template = new TemplateAssetCatalogService.BoundTemplate(
        "sql:customer@asset", "sql:customer", "客户查询", "查询客户", "客户数据库", "客户分析",
        Map.of("type", "object", "properties", Map.of("customerId", Map.of("type", "string")), "required", List.of("customerId"), "additionalProperties", false),
        "revision", "sql_query_execute", Map.of("datasourceId", "asset", "template", "customer"));

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        service = new TemplateCapabilityService(repository, catalog, callers, json, mock(JdbcTemplate.class), registry, permissions, credentials, mock(ObjectProvider.class));
        when(catalog.businessTemplates(false)).thenReturn(List.of(template));
        when(credentials.resolvedSecret()).thenReturn("secret");
        when(repository.lockLogical(anyString())).thenAnswer(call -> rows.stream().filter(row -> row.getLogicalId().equals(call.getArgument(0))).toList());
        when(repository.saveAndFlush(any())).thenAnswer(call -> { ToolRelease row = call.getArgument(0); if (!rows.contains(row)) rows.add(row); return row; });
        when(repository.findByState("RUNNING")).thenAnswer(call -> rows.stream().filter(row -> "RUNNING".equals(row.getState())).toList());
        when(repository.findByTenantIdOrderByCreatedAtDesc(anyString())).thenAnswer(call -> rows.stream().filter(row -> row.getTenantId().equals(call.getArgument(0))).toList());
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clearSynchronization(); }
    Map<String,Object> publish(String user) { return service.publish("tenant", user, template.key(), template.revision()); }
    Map<String,Object> proof(String agent) { return McpCallerSignature.issue("secret", "tenant", "user", agent); }
    void allowAgent(String name) { when(permissions.allowedIdsForAgent(eq("AGENT_TOOL"), eq("tenant"), eq("user"), anySet(), eq(Set.of(name)), eq("agent"))).thenReturn(Set.of(name)); }

    @Test void publicationIsIdempotentAndIsolatedByPublisher() {
        var first = publish("user"); var repeated = publish("user"); var other = publish("other");
        assertEquals(first, repeated); assertEquals(2, rows.size());
        assertEquals("客户分析", first.get("category"));
        assertEquals("查询客户", first.get("description"));
        assertNotEquals(first.get("toolName"), other.get("toolName"));
        assertEquals(List.of(first), service.publications("tenant", "user"));
        assertTrue(service.publications("otherTenant", "user").isEmpty());
    }
    @Test void rejectsStaleVersionAndUnauthorizedPublicationBeforeSaving() {
        assertThrows(IllegalArgumentException.class, () -> service.publish("tenant", "user", template.key(), "stale"));
        when(catalog.businessTemplates(false)).thenReturn(List.of());
        assertThrows(SecurityException.class, () -> publish("user")); assertTrue(rows.isEmpty());
    }
    @Test void discoveryAndExecutionRecheckAgentTemplateAssetAndSignedIdentity() {
        String name = publish("user").get("toolName").toString(); ToolRelease row = rows.get(0); allowAgent(name);
        assertTrue(service.available(row, proof("agent")));
        assertFalse(service.available(row, proof("unbound"))); assertFalse(service.available(row, proof("")));
        var forged = new HashMap<>(proof("agent")); forged.put("userId", "other"); assertFalse(service.available(row, forged));
        when(catalog.businessTemplates(false)).thenReturn(List.of());
        assertFalse(service.available(row, proof("agent")));
        assertThrows(SecurityException.class, () -> service.invoke(name, Map.of("customerId", "1"), Map.of(McpCallerSignature.KEY, proof("agent"))));
        verifyNoInteractions(registry);
    }
    @Test void callsOnlyFixedExecutorAndRejectsAssetParameterInjection() {
        String name = publish("user").get("toolName").toString(); allowAgent(name);
        when(registry.executeEnhancedTool(eq("sql_query_execute"), any())).thenReturn(ToolOutput.success(Map.of("rows", List.of())));
        service.invoke(name, Map.of("customerId", "1", "userId", "forged"), Map.of(McpCallerSignature.KEY, proof("agent")));
        var arguments = ArgumentCaptor.forClass(ToolInput.class); verify(registry).executeEnhancedTool(eq("sql_query_execute"), arguments.capture());
        assertEquals("asset", arguments.getValue().getParameters().get("datasourceId"));
        assertEquals("customer", arguments.getValue().getParameters().get("template"));
        assertEquals("user", arguments.getValue().getUserId());
        assertThrows(IllegalArgumentException.class, () -> service.invoke(name, Map.of("customerId", "1", "datasourceId", "forged"), Map.of(McpCallerSignature.KEY, proof("agent"))));
    }
    @Test void offlineImmediatelyDeniesExistingToolAndCannotCrossOwners() {
        var publication = publish("user"); var row = rows.get(0); allowAgent(publication.get("toolName").toString());
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        assertThrows(SecurityException.class, () -> service.offline("tenant", "other", row.getId()));
        service.offline("tenant", "user", row.getId()); assertFalse(service.available(row, proof("agent")));
        assertThrows(SecurityException.class, () -> service.invoke(publication.get("toolName").toString(), Map.of(), Map.of()));
    }
}
