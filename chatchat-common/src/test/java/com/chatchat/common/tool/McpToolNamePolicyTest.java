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
}
