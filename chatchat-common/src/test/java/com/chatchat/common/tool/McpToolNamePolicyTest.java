package com.chatchat.common.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolNamePolicyTest {

    @Test
    void usesApiWorkflowReviewSemanticsForTransportNames() {
        assertThat(McpToolNamePolicy.workflowSemanticKey(
            "mcp_chatchat_mcp_server_API-Template-Query"))
            .isEqualTo("api_template_query");
    }

    @Test
    void rejectsNamesThatBecomeAmbiguousDuringWorkflowReview() {
        assertThatThrownBy(() -> McpToolNamePolicy.auditPublicationNames(List.of(
            "api-template-query", "mcp_chatchat_mcp_server_api_template_query")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ambiguous to workflow review")
            .hasMessageContaining("api_template_query");
    }

    @Test
    void rejectsInvalidWireNameBeforePublication() {
        assertThatThrownBy(() -> McpToolNamePolicy.requirePublishableName(" api template query "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("whitespace");
    }

    @Test
    void classifiesEveryPublicDomainBridgeAsTemplateDiscoveryAcrossTransportNames() {
        assertThat(List.of(
            "api_service_query",
            "server_capability_query",
            "http_capability_query",
            "jmx_capability_query",
            "database_capability_query",
            "data_query_query",
            "python_analysis_query"
        )).allSatisfy(name -> {
            assertThat(McpToolNamePolicy.isTemplateDiscovery(name)).isTrue();
            assertThat(McpToolNamePolicy.isTemplateDiscovery("mcp_chatchat_mcp_server_" + name)).isTrue();
            assertThat(McpToolNamePolicy.isTemplateDiscoveryBridge(name)).isTrue();
        });
    }

    @Test
    void keepsDiscoveryAndExecutionRolesMutuallyAccurate() {
        assertThat(McpToolNamePolicy.isAssetDiscovery("mcp_vendor_ssh_asset_query")).isTrue();
        assertThat(McpToolNamePolicy.isTemplateDiscovery("mcp_vendor_ssh_template_query")).isTrue();
        assertThat(McpToolNamePolicy.isTemplateExecution("mcp_vendor_linux_command_execute")).isTrue();
        assertThat(McpToolNamePolicy.isTemplateDiscovery("mcp_vendor_linux_command_execute")).isFalse();
    }
}
