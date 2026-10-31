package com.chatchat.mcpserver.templatepublication.publisher;

import com.chatchat.mcpserver.templatepublication.admin.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import io.modelcontextprotocol.server.McpSyncServer;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TemplateCapabilityPublisherTest {
    @Test void onlySuccessfullyTestedStudioReadQueriesReceiveLowRiskWhileControlledPolicyRemains() throws Exception {
        var capabilities=mock(TemplateCapabilityService.class);var json=new ObjectMapper();
        ToolRelease verified=row("verified"),unverified=row("unverified"),other=row("other");
        when(capabilities.running()).thenReturn(List.of(verified,unverified,other));
        when(capabilities.definition(verified)).thenReturn(json.readTree("{\"binding\":{\"sqlRevisionId\":\"sql1\"},\"executor\":\"sql_query_execute\",\"sqlTest\":{\"success\":true},\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}"));
        when(capabilities.definition(unverified)).thenReturn(json.readTree("{\"binding\":{\"sqlRevisionId\":\"sql2\"},\"executor\":\"sql_query_execute\",\"sqlTest\":{\"success\":false},\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}"));
        when(capabilities.definition(other)).thenReturn(json.readTree("{\"binding\":{},\"executor\":\"linux_command_execute\",\"sqlTest\":{\"success\":true},\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}"));
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> server=mock(ObjectProvider.class);
        var published=new TemplateCapabilityPublisher(capabilities,server,json).contribute();
        assertThat(published.get(0).specification().tool().meta()).containsEntry("riskLevel","low").containsEntry("runtimeLevel","controlled").containsEntry("operationType","execute");
        assertThat(published.get(1).specification().tool().meta()).containsEntry("riskLevel","high");
        assertThat(published.get(2).specification().tool().meta()).containsEntry("riskLevel","high");
    }
    private ToolRelease row(String id) {
        var row=new ToolRelease();row.setId(id);row.setLogicalId(UUID.randomUUID().toString());row.setTenantId("tenant");row.setTitle(id);row.setReleaseNumber(1);row.setOutputSchemaJson("{\"type\":\"object\"}");return row;
    }
}
