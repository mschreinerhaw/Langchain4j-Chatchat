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
        verifyNoInteractions(tools);
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

    @Test void doesNotReadRawOrMismatchedSqlRows() {
        assertThatIllegalArgumentException().isThrownBy(() -> NativePublishedTemplate.sqlData(json, """
            {"schemaVersion":"mcp_analysis_payload.v1","data":{},"rawData":{"rowCount":1,"rows":[{}]}}
            """));
        assertThatIllegalArgumentException().isThrownBy(() -> NativePublishedTemplate.sqlData(json, """
            {"data":{"rowCount":2,"rows":[{}]}}
            """));
    }
}
