package com.chatchat.agents.protocol;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolProtocolDriverContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolProtocolContractResolverTest {

    @Test
    void injectsAnUnknownToolsPublishedContractWithoutKernelToolKnowledge() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String tool = "tenant_semantic_gateway";
        when(registry.getToolMetadata(tool)).thenReturn(metadata(Map.of(
            ToolProtocolDriverContract.METADATA_KEY,
            ToolProtocolDriverContract.of(
                "tenant.semantic.v1",
                List.of("Planner must use the signed semantic selector."),
                List.of("Rewriter must preserve the signed selector."))
        )));
        ToolProtocolContractResolver resolver = new ToolProtocolContractResolver();

        assertThat(resolver.plannerSection(List.of(tool), registry))
            .contains("tenant.semantic.v1 via " + tool)
            .contains("Planner must use the signed semantic selector.")
            .doesNotContain("Rewriter must preserve");
        assertThat(resolver.rewriterSection(List.of(tool), registry))
            .contains("Rewriter must preserve the signed selector.")
            .doesNotContain("Planner must use");
    }

    @Test
    void readsRemoteMcpContractAndIgnoresContractsFromUnavailableTools() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String available = "mcp_tenant_custom_execute";
        String unavailable = "mcp_tenant_hidden_execute";
        Map<String, Object> availableContract = ToolProtocolDriverContract.of(
            "remote.custom.v1", List.of("AVAILABLE_RULE"), List.of());
        Map<String, Object> hiddenContract = ToolProtocolDriverContract.of(
            "remote.hidden.v1", List.of("HIDDEN_RULE"), List.of());
        when(registry.getToolMetadata(available)).thenReturn(metadata(Map.of(
            "mcpToolMeta", Map.of(ToolProtocolDriverContract.METADATA_KEY, availableContract))));
        when(registry.getToolMetadata(unavailable)).thenReturn(metadata(Map.of(
            ToolProtocolDriverContract.METADATA_KEY, hiddenContract)));

        String section = new ToolProtocolContractResolver().plannerSection(List.of(available), registry);

        assertThat(section).contains("AVAILABLE_RULE").doesNotContain("HIDDEN_RULE");
    }

    @Test
    void rejectsMalformedContractsAndKeepsLegacyDeploymentCompatibility() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String malformed = "custom_gateway";
        String legacySql = "mcp_chatchat_mcp_server_sql_query_execute";
        when(registry.getToolMetadata(malformed)).thenReturn(metadata(Map.of(
            ToolProtocolDriverContract.METADATA_KEY, Map.of(
                "schemaVersion", "unknown.v9",
                "driverId", "bad id with spaces",
                "plannerRules", List.of("MALFORMED_RULE"))
        )));

        ToolProtocolContractResolver resolver = new ToolProtocolContractResolver();

        assertThat(resolver.plannerSection(List.of(malformed), registry)).isEmpty();
        assertThat(resolver.plannerSection(List.of(legacySql), registry))
            .contains("legacy.sql-template.v1")
            .contains("registered template")
            .doesNotContain("MALFORMED_RULE");
    }

    private ToolMetadata metadata(Map<String, Object> values) {
        return ToolMetadata.builder().id("tool").metadata(values).build();
    }
}
