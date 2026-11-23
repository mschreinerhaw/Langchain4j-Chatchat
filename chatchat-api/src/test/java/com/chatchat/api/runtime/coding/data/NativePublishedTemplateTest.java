package com.chatchat.api.runtime.coding.data;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.chatchat.api.runtime.analysis.RegisteredToolAnalysisOperator;
import com.chatchat.api.runtime.coding.nativecline.NativeS3Evidence;
import com.chatchat.api.service.AgentTemplateToolPolicy;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NativePublishedTemplateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void deploymentGrantAndRunningPublicationDoNotOverrideLiveAgentToolDenial() throws Exception {
        var scope = new KernelDataScope("tenant", "user", null, null, "run", null, Map.of());
        var policy = new CodingDataSupplyProperties();
        policy.setClineTemplateInvocationEnabled(true);
        var supply = mock(CodingDataSupplyService.class);
        var tools = mock(RegisteredToolAnalysisOperator.class);
        var publications = mock(McpControlPlaneClient.class);
        var permissions = mock(AgentTemplateToolPolicy.class);
        var service = new NativePublishedTemplate(policy, supply, mock(DurableCodingTaskService.class), tools,
            publications, permissions, mock(SkillExecutionScopePort.class), mock(NativeS3Evidence.class), json);
        when(supply.grants(scope)).thenReturn(List.of(new CodingDataSupplyProperties.Grant(
            "tenant", "user", "agent", "SQL", "Warehouse", "DEV", Map.of(), List.of())));
        when(publications.request("GET", "/api/v1/template-capabilities/publications?tenantId=tenant&actorId=user", null))
            .thenReturn(json.readTree("""
                [{"state":"RUNNING","verifiedReadOnlySql":true,"templateKey":"sql_datasource:SQL",
                  "assetName":"Warehouse","toolName":"tpl_0123456789abcdef0123456789abcdef"}]
                """));
        when(permissions.allowed("tenant", "user", "agent", Set.of("tpl_0123456789abcdef0123456789abcdef")))
            .thenReturn(Set.of());

        assertThatThrownBy(() -> service.capture(scope, "agent", "SQL"))
            .isInstanceOf(SecurityException.class).hasMessageContaining("resource policy");
        assertThat(service.captureOptional(scope, "agent", Set.of("SQL"), "Original question")).isEmpty();
        assertThat(service.captureOptional(scope, "agent", Set.of("unpublished"), "Original question")).isEmpty();
        assertThat(service.captureOptional(scope, "another-agent", Set.of("SQL"), "Original question")).isEmpty();
        verifyNoInteractions(tools);
    }

    @Test void freezesEveryLiveOptionalGrantForTheOriginalQuestion() throws Exception {
        var scope = new KernelDataScope("tenant", "user", null, null, "run", null, Map.of());
        var policy = new CodingDataSupplyProperties();
        policy.setClineTemplateInvocationEnabled(true);
        var supply = mock(CodingDataSupplyService.class);
        when(supply.hash(org.mockito.ArgumentMatchers.any())).thenReturn("grant-digest");
        var tools = mock(RegisteredToolAnalysisOperator.class);
        var publications = mock(McpControlPlaneClient.class);
        var permissions = mock(AgentTemplateToolPolicy.class);
        var service = new NativePublishedTemplate(policy, supply, mock(DurableCodingTaskService.class), tools,
            publications, permissions, mock(SkillExecutionScopePort.class), mock(NativeS3Evidence.class), json);
        when(supply.grants(scope)).thenReturn(List.of("A", "B", "NOT_OPTIONAL").stream()
            .map(id -> new CodingDataSupplyProperties.Grant("tenant", "user", "agent", id, "Warehouse",
                "DEV", Map.of(), List.of("warehouse"))).toList());
        when(publications.request("GET", "/api/v1/template-capabilities/publications?tenantId=tenant&actorId=user", null))
            .thenReturn(json.readTree("""
                [{"state":"RUNNING","verifiedReadOnlySql":true,"templateKey":"sql_datasource:A",
                  "assetName":"Warehouse","toolName":"tool_a","parameterSchema":{"type":"object"}},
                 {"state":"RUNNING","verifiedReadOnly":true,"templateKey":"jmx_endpoint:B",
                  "assetName":"Warehouse","toolName":"tool_b","parameterSchema":{"type":"object"}}]
                """));
        for (String tool : List.of("tool_a", "tool_b")) {
            when(permissions.allowed("tenant", "user", "agent", Set.of(tool))).thenReturn(Set.of(tool));
            when(tools.resolveBoundPublishedTool("agent", tool)).thenReturn(java.util.Optional.of(tool));
        }

        var binding = service.captureOptional(scope, "agent", Set.of("A", "B", "FOREIGN"), "Original question");
        assertThat(binding).isPresent();
        assertThat(binding.get()).containsEntry("originalQuestion", "Original question");
        var ids = new java.util.ArrayList<String>();
        json.valueToTree(binding.get()).path("optionalBindings").fieldNames().forEachRemaining(ids::add);
        assertThat(ids).containsExactlyInAnyOrder("A", "B");
    }

    @Test void extractsSqlRowsFromTheGovernedPublishedToolEnvelope() throws Exception {
        var data = NativePublishedTemplate.sqlData(json, """
            {"schemaVersion":"mcp_analysis_payload.v1","data":{"success":true,"data":{
              "dataSchema":"sql_result.v1","data":{"complete":true,"rowCount":1,"rows":[{"warehouse":3}]}}},
              "rawData":{"rows":[{"warehouse":9}]}}
            """);
        assertThat(data.path("rows").get(0).path("warehouse").asInt()).isEqualTo(3);
        assertThat(data.path("complete").asBoolean()).isTrue();
    }

    @Test void outerPartialAndPreviewReceiptsOverrideNestedCompleteness() throws Exception {
        for (String status : List.of("PARTIAL", "PREVIEW")) {
            var data = NativePublishedTemplate.sqlData(json, """
                {"schemaVersion":"mcp_analysis_payload.v1","status":"%s",
                 "data":{"data":{"rowCount":1,"rows":[{"warehouse":3}],"complete":true}}}
                """.formatted(status));
            assertThat(data.path("complete").asBoolean()).isFalse();
            assertThat(data.path("rows").size()).isEqualTo(1);
        }
    }

    @Test void normalizesCursorRecordsAndNeverUsesTheRawEnvelope() throws Exception {
        var result = NativePublishedTemplate.structuredData(json, """
            {"schemaVersion":"mcp_analysis_payload.v1","data":{"records":[{"measure":1}],"complete":true},
             "rawData":{"rows":[{"measure":99}]}}
            """, List.of("measure"));
        assertThat(result.path("rows").get(0).path("measure").asInt()).isEqualTo(1);
        assertThat(result.path("complete").asBoolean()).isTrue();
    }

    @Test void preservesPartialAndUnknownCompletenessOnStructuredData() throws Exception {
        for (String payload : List.of(
                "{\"status\":\"PARTIAL\",\"data\":{\"complete\":true,\"metrics\":[{\"value\":3}]}}",
                "{\"metrics\":[{\"value\":3}],\"metricCount\":2,\"complete\":true}",
                "{\"metrics\":[{\"value\":3}],\"complete\":true,\"errorCount\":1}",
                "{\"data\":[{\"value\":3}]}")) {
            var result = NativePublishedTemplate.structuredData(json, payload, List.of("value"));
            assertThat(result.path("rows").size()).isEqualTo(1);
            assertThat(result.path("complete").asBoolean()).isFalse();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> NativePublishedTemplate.structuredData(json, """
            {"schemaVersion":"mcp_analysis_payload.v1","rawData":{"rows":[{"value":3}]},"data":{}}
            """, List.of("value")));
    }

    @Test void unwrapsGovernedHttpBodyAndKeepsTransportCompletenessSeparate() throws Exception {
        var result = NativePublishedTemplate.structuredData(json, """
            {"schemaVersion":"mcp_analysis_payload.v1","data":{"dataSchema":"api_response.v1","data":{
              "headers":{"Authorization":"secret"},"rawBody":"discard",
              "bodyCompleteness":{"gatewayTruncated":false,"upstreamCompleteness":"UNKNOWN"},
              "body":{"records":[{"metric":19}]}
            }}}
            """, List.of("metric"));
        assertThat(result.path("rowCount").asInt()).isEqualTo(1);
        assertThat(result.path("rows").get(0).path("metric").asInt()).isEqualTo(19);
        assertThat(result.path("completenessStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(result.toString()).doesNotContain("Authorization", "secret", "discard");
    }

    @Test void doesNotReadRawOrMismatchedSqlRows() {
        assertThatIllegalArgumentException().isThrownBy(() -> NativePublishedTemplate.sqlData(json, """
            {"schemaVersion":"mcp_analysis_payload.v1","data":{},"rawData":{"rowCount":1,"rows":[{}]}}
            """));
        assertThatIllegalArgumentException().isThrownBy(() -> NativePublishedTemplate.sqlData(json, """
            {"data":{"rowCount":2,"rows":[{}]}}
            """));
    }
}
