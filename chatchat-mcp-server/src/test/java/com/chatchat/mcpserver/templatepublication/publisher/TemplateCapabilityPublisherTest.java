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
        when(capabilities.definition(verified)).thenReturn(json.readTree("{\"templateKey\":\"sql_datasource:test\",\"binding\":{\"permissionLevel\":\"read\",\"sqlRevisionId\":\"sql1\"},\"executor\":\"sql_query_execute\",\"sqlTest\":{\"success\":true},\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}"));
        when(capabilities.definition(unverified)).thenReturn(json.readTree("{\"templateKey\":\"sql_datasource:test\",\"binding\":{\"permissionLevel\":\"read\",\"sqlRevisionId\":\"sql2\"},\"executor\":\"sql_query_execute\",\"sqlTest\":{\"success\":false},\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}"));
        when(capabilities.definition(other)).thenReturn(json.readTree("{\"binding\":{},\"executor\":\"linux_command_execute\",\"sqlTest\":{\"success\":true},\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}"));
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> server=mock(ObjectProvider.class);
        var published=new TemplateCapabilityPublisher(capabilities,server,json).contribute();
        assertThat(published.get(0).specification().tool().meta()).containsEntry("riskLevel","low").containsEntry("runtimeLevel","controlled").containsEntry("operationType","execute");
        assertThat(published.get(1).specification().tool().meta()).containsEntry("riskLevel","high");
        assertThat(published.get(2).specification().tool().meta()).containsEntry("riskLevel","high");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void publishesOnlySuccessfulTrialColumnIdentifiersWithoutSampleValues(boolean success) throws Exception {
        var capabilities=mock(TemplateCapabilityService.class);var json=new ObjectMapper();var row=row("generic");
        when(capabilities.running()).thenReturn(List.of(row));
        var definition=json.createObjectNode().put("templateKey","sql_datasource:test");
        definition.putObject("binding").put("sqlRevisionId","revision").put("permissionLevel","read");
        definition.put("executor","sql_query_execute");definition.putObject("inputSchema").put("type","object").putObject("properties");
        var evidence=definition.putObject("sqlTest").put("success",success);var preview=evidence.putObject("preview");
        preview.putArray("columns").add("region_key").add("minimum_id");
        preview.putArray("rows").addObject().put("region_key","PRIVATE_SAMPLE_MUST_NOT_BE_PUBLISHED").put("minimum_id",937);
        when(capabilities.definition(row)).thenReturn(definition);
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> server=mock(ObjectProvider.class);
        var tool=new TemplateCapabilityPublisher(capabilities,server,json).contribute().get(0).specification().tool();
        assertThat(tool.description()).doesNotContain("PRIVATE_SAMPLE_MUST_NOT_BE_PUBLISHED","937");
        if(success){assertThat(tool.meta()).containsEntry("sqlResultColumns",List.of("region_key","minimum_id"));
            assertThat(tool.description()).contains("minimum_id","Identifiers only","not executable output paths");}
        else {assertThat(tool.meta()).doesNotContainKey("sqlResultColumns");assertThat(tool.description()).doesNotContain("minimum_id");}
    }
    @Test void admitsOnlyReadOnlyFixedTargetsInSupportedExecutorFamilies() {
        var json = new ObjectMapper();
        var families = Map.of(
            "api_template_execute", Map.of("key", "api_service:query", "templateId", "query"),
            "http_request_execute", Map.of("key", "http_endpoint:query", "endpointId", "e1", "template", "query"),
            "jmx_monitor_execute", Map.of("key", "jmx_endpoint:query", "template", "query"),
            "linux_command_execute", Map.of("key", "ssh_host:query", "hostId", "h1", "template", "query"));
        families.forEach((executor, fields) -> {
            var snapshot = json.createObjectNode().put("templateKey", fields.get("key")).put("executor", executor);
            var binding = snapshot.putObject("binding").put("permissionLevel", "read");
            fields.forEach((key, value) -> { if (!"key".equals(key)) binding.put(key, value); });
            assertThat(TemplateCapabilityService.verifiedReadOnly(snapshot)).isTrue();
            binding.put("permissionLevel", "write");
            assertThat(TemplateCapabilityService.verifiedReadOnly(snapshot)).isFalse();
            binding.put("permissionLevel", "read").remove(java.util.List.of("template", "templateId"));
            assertThat(TemplateCapabilityService.verifiedReadOnly(snapshot)).isFalse();
        });
    }
    private ToolRelease row(String id) {
        var row=new ToolRelease();row.setId(id);row.setLogicalId(UUID.randomUUID().toString());row.setTenantId("tenant");row.setTitle(id);row.setReleaseNumber(1);row.setOutputSchemaJson("{\"type\":\"object\"}");return row;
    }
}
