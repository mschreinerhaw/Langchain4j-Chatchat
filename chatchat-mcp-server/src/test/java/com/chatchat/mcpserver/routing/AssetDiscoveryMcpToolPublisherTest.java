package com.chatchat.mcpserver.routing;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AssetDiscoveryMcpToolPublisherTest {

    @Test
    void assetQueryToolIsReadOnlyAndAutoExecute() throws Exception {
        AssetDiscoveryMcpToolPublisher publisher = new AssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(AssetDiscoveryService.class)
        );
        Method assetQueryTool = AssetDiscoveryMcpToolPublisher.class.getDeclaredMethod("assetQueryTool");
        assetQueryTool.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) assetQueryTool.invoke(publisher);
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> confirmation = (Map<?, ?>) meta.get("confirmation");

        assertThat(tool.name()).isEqualTo(AssetDiscoveryMcpToolPublisher.TOOL_NAME);
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("readOnly")).isEqualTo(true);
        assertThat(meta.get("riskLevel")).isEqualTo("low");
        assertThat(confirmation.get("default")).isEqualTo("auto_execute");
        assertThat(confirmation.get("allow_user_override")).isEqualTo(false);
    }
}
