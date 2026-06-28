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
    void sshAssetQueryToolIsTypedReadOnlyAndAutoExecute() throws Exception {
        AssetDiscoveryMcpToolPublisher publisher = new AssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(AssetDiscoveryService.class)
        );
        Method assetQueryTool = AssetDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "assetQueryTool", String.class, String.class, String.class, String.class, String.class);
        assetQueryTool.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) assetQueryTool.invoke(
                publisher,
                AssetDiscoveryMcpToolPublisher.SSH_ASSET_TOOL_NAME,
                "SSH asset metadata discovery",
                "Read-only discovery tool for querying redacted SSH host asset metadata and routing hints.",
                "ssh_host",
                "host"
            );
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> confirmation = (Map<?, ?>) meta.get("confirmation");
        Map<?, ?> boundary = (Map<?, ?>) meta.get("toolBoundary");
        Map<?, ?> indexPolicy = (Map<?, ?>) meta.get("indexPolicy");

        assertThat(tool.name()).isEqualTo(AssetDiscoveryMcpToolPublisher.SSH_ASSET_TOOL_NAME);
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("readOnly")).isEqualTo(true);
        assertThat(meta.get("riskLevel")).isEqualTo("low");
        assertThat(meta.get("targetKind")).isEqualTo("host");
        assertThat(meta.get("assetType")).isEqualTo("ssh_host");
        assertThat(boundary.get("rejectCrossTypeRouting")).isEqualTo(true);
        assertThat(indexPolicy.get("logicalIndex")).isEqualTo("asset:ssh_host");
        assertThat(confirmation.get("default")).isEqualTo("auto_execute");
        assertThat(confirmation.get("allow_user_override")).isEqualTo(false);
    }

    @Test
    void typedAssetArgumentsForceAssetTypeAndTargetKind() throws Exception {
        AssetDiscoveryMcpToolPublisher publisher = new AssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(AssetDiscoveryService.class)
        );
        Method argumentsMethod = AssetDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "forcedAssetArguments", Map.class, String.class, String.class, String.class);
        argumentsMethod.setAccessible(true);

        Map<?, ?> arguments = (Map<?, ?>) argumentsMethod.invoke(publisher, Map.of(
            "assetType", "http_endpoint",
            "finalDecision", "http",
            "filters", Map.of("assetName", "prod-db")
        ), AssetDiscoveryMcpToolPublisher.SQL_DATASOURCE_ASSET_TOOL_NAME, "sql_datasource", "database");

        assertThat(arguments.get("assetType")).isEqualTo("sql_datasource");
        assertThat(arguments.get("finalDecision")).isEqualTo("database");
        assertThat(arguments.get("confidence")).isEqualTo(1.0);
        assertThat(arguments.get("filters").toString()).contains("prod-db");
        assertThat(arguments.get("candidates").toString()).contains("database");
    }
}
