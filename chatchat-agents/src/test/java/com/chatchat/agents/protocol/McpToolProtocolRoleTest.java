package com.chatchat.agents.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolProtocolRoleTest {

    @Test
    void resolvesStableSystemSuffixesWithoutDependingOnServicePrefix() {
        assertThat(McpToolProtocolRole.resolve("mcp_any_server_api_asset_query"))
            .contains(McpToolProtocolRole.ASSET_QUERY);
        assertThat(McpToolProtocolRole.resolve("mcp_other_api_template_query"))
            .contains(McpToolProtocolRole.TEMPLATE_QUERY);
        assertThat(McpToolProtocolRole.resolve("tenant-api-template-execute"))
            .contains(McpToolProtocolRole.TEMPLATE_EXECUTE);
        assertThat(McpToolProtocolRole.resolve("unrelated_execute")).isEmpty();
    }

    @Test
    void extractsTheSameFamilyAcrossTheTemplateProtocolChain() {
        String asset = "mcp_chatchat_mcp_server_api_asset_query";
        String query = "mcp_chatchat_mcp_server_api_template_query";
        String execute = "mcp_chatchat_mcp_server_api_template_execute";

        assertThat(McpToolProtocolRole.ASSET_QUERY.family(asset))
            .isEqualTo(McpToolProtocolRole.TEMPLATE_QUERY.family(query))
            .isEqualTo(McpToolProtocolRole.TEMPLATE_EXECUTE.family(execute));
    }
}
