package com.chatchat.common.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolWorkflowContractTest {

    @Test
    void arbitraryToolNameResolvesFromPublishedMetadata() {
        ToolMetadata metadata = ToolMetadata.builder().metadata(Map.of(
            ToolWorkflowContract.METADATA_KEY, Map.of(
                "schemaVersion", ToolWorkflowContract.SCHEMA_VERSION,
                "workflowRole", "template_discovery",
                "protocolFamily", "tenant-defined"
            )
        )).build();

        assertThat(ToolWorkflowContract.resolveRole("mcp_vendor_x9f37", metadata))
            .isEqualTo(ToolWorkflowRole.TEMPLATE_DISCOVERY);
    }

    @Test
    void declaredRoleOverridesLegacySemanticName() {
        ToolMetadata metadata = ToolMetadata.builder().metadata(Map.of(
            ToolWorkflowContract.METADATA_KEY, Map.of(
                "schemaVersion", ToolWorkflowContract.SCHEMA_VERSION,
                "workflowRole", "template_execution"
            )
        )).build();

        ToolWorkflowContract.validate("tenant_asset_query", metadata);
        assertThat(ToolWorkflowContract.resolveRole("tenant_asset_query", metadata))
            .isEqualTo(ToolWorkflowRole.TEMPLATE_EXECUTION);
    }

    @Test
    void legacyToolsRemainCompatibleDuringMigration() {
        assertThat(ToolWorkflowContract.resolveRole("server_capability_query", null))
            .isEqualTo(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        assertThat(ToolWorkflowContract.resolveRole("plain_calculator", null))
            .isEqualTo(ToolWorkflowRole.DIRECT);
    }
}
