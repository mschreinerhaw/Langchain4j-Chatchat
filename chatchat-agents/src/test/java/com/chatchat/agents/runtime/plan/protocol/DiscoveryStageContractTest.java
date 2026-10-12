package com.chatchat.agents.runtime.plan.protocol;

import com.chatchat.common.tool.ToolWorkflowRole;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryStageContractTest {
    private final ToolProtocolPayloadNavigator navigator = new ToolProtocolPayloadNavigator();

    @Test void discoveryBridgeUsesTheReturnedStageInsideTransportEnvelopes() {
        assertThat(navigator.discoveryRole(Map.of("data", Map.of("stage", "asset_selection",
            "assetSelectionRequired", true, "templates", java.util.List.of())), ToolWorkflowRole.TEMPLATE_DISCOVERY))
            .isEqualTo(ToolWorkflowRole.ASSET_DISCOVERY);
        assertThat(navigator.discoveryRole("{\"structuredContent\":{\"stage\":\"template\"}}",
            ToolWorkflowRole.ASSET_DISCOVERY)).isEqualTo(ToolWorkflowRole.TEMPLATE_DISCOVERY);
    }

    @Test void returnedStageCannotTurnAnExecutionToolIntoDiscovery() {
        assertThat(navigator.discoveryRole(Map.of("stage", "asset_selection"), ToolWorkflowRole.TEMPLATE_EXECUTION))
            .isEqualTo(ToolWorkflowRole.TEMPLATE_EXECUTION);
        assertThat(navigator.discoveryRole(Map.of("stage", "unexpected"), ToolWorkflowRole.TEMPLATE_DISCOVERY))
            .isEqualTo(ToolWorkflowRole.TEMPLATE_DISCOVERY);
    }

    @Test void usesPublishedResultSchemaWhenTheBridgeOmitsStage() {
        var assets = Map.of("schemaVersion", "mcp_analysis_payload.v1", "data",
            Map.of("schemaVersion", "asset_query_result.v1", "returnedCount", 0, "assets", java.util.List.of()));
        assertThat(navigator.discoveryRole(assets, ToolWorkflowRole.TEMPLATE_DISCOVERY))
            .isEqualTo(ToolWorkflowRole.ASSET_DISCOVERY);
        assertThat(navigator.discoveryRole(Map.of("data", Map.of("schemaVersion", "template_query_result.v1")),
            ToolWorkflowRole.ASSET_DISCOVERY)).isEqualTo(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        assertThat(navigator.discoveryRole(assets, ToolWorkflowRole.DIRECT)).isEqualTo(ToolWorkflowRole.DIRECT);
    }
}
