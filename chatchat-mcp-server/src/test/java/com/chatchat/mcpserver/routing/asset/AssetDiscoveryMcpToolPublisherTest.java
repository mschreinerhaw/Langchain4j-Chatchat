package com.chatchat.mcpserver.routing.asset;

import com.chatchat.mcpserver.routing.target.TargetKindRegistry;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AssetDiscoveryMcpToolPublisherTest {

    @Test
    void refreshKeepsTypedAssetDiscoveryInternal() {
        McpSyncServer server = mock(McpSyncServer.class);
        AssetDiscoveryMcpToolPublisher publisher = new AssetDiscoveryMcpToolPublisher(
            server,
            mock(AssetDiscoveryService.class),
            new TargetKindRegistry()
        );

        publisher.refresh();

        verify(server, never()).addTool(org.mockito.ArgumentMatchers.any());
        verify(server).removeTool(AssetDiscoveryMcpToolPublisher.SQL_DATASOURCE_ASSET_TOOL_NAME);
    }

    @Test
    void sshAssetQueryToolIsTypedReadOnlyAndAutoExecute() throws Exception {
        AssetDiscoveryMcpToolPublisher publisher = new AssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(AssetDiscoveryService.class),
            new TargetKindRegistry()
        );
        Method assetQueryTool = AssetDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "assetQueryTool", String.class, String.class, String.class, String.class, String.class, String.class);
        assetQueryTool.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) assetQueryTool.invoke(
                publisher,
                AssetDiscoveryMcpToolPublisher.SSH_ASSET_TOOL_NAME,
                "SSH asset metadata discovery",
                "Read-only discovery tool for querying redacted SSH host asset metadata and routing hints.",
                "ssh_host",
                "host",
                null
            );
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> confirmation = (Map<?, ?>) meta.get("confirmation");
        Map<?, ?> boundary = (Map<?, ?>) meta.get("toolBoundary");
        Map<?, ?> indexPolicy = (Map<?, ?>) meta.get("indexPolicy");
        Map<?, ?> routingProtocol = (Map<?, ?>) meta.get("routingProtocol");

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
        assertThat(((List<?>) routingProtocol.get("allowedFilterFields")).stream().map(String::valueOf).toList())
            .contains("assetname", "intent", "queryterms", "retrievalsignals");
    }

    @Test
    void typedAssetArgumentsForceAssetTypeAndTargetKind() throws Exception {
        AssetDiscoveryMcpToolPublisher publisher = new AssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(AssetDiscoveryService.class),
            new TargetKindRegistry()
        );
        Method argumentsMethod = AssetDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "forcedAssetArguments", Map.class, String.class, String.class, String.class, String.class);
        argumentsMethod.setAccessible(true);

        Map<?, ?> arguments = (Map<?, ?>) argumentsMethod.invoke(publisher, Map.of(
            "assetType", "http_endpoint",
            "finalDecision", "http",
            "filters", Map.of("assetName", "prod-db")
        ), AssetDiscoveryMcpToolPublisher.SQL_DATASOURCE_ASSET_TOOL_NAME, "sql_datasource", "database", null);

        assertThat(arguments.get("assetType")).isEqualTo("sql_datasource");
        assertThat(arguments.get("finalDecision")).isEqualTo("database");
        assertThat(arguments.get("confidence")).isEqualTo(1.0);
        assertThat(arguments.get("filters").toString()).contains("prod-db");
        assertThat(arguments.get("candidates").toString()).contains("database");
    }

    @Test
    void httpDiscoveryToolsForceSeparateTechnicalTypes() throws Exception {
        AssetDiscoveryMcpToolPublisher publisher = new AssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class), mock(AssetDiscoveryService.class), new TargetKindRegistry());
        Method argumentsMethod = AssetDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "forcedAssetArguments", Map.class, String.class, String.class, String.class, String.class);
        argumentsMethod.setAccessible(true);

        Map<?, ?> http = (Map<?, ?>) argumentsMethod.invoke(publisher, Map.of("technicalType", "MICROSERVICE"),
            AssetDiscoveryMcpToolPublisher.HTTP_ENDPOINT_ASSET_TOOL_NAME, "http_endpoint", "http", "HTTP");
        Map<?, ?> microservice = (Map<?, ?>) argumentsMethod.invoke(publisher, Map.of("technicalType", "HTTP"),
            AssetDiscoveryMcpToolPublisher.MICROSERVICE_ASSET_TOOL_NAME, "http_endpoint", "http", "MICROSERVICE");

        assertThat(http.get("technicalType")).isEqualTo("HTTP");
        assertThat(microservice.get("technicalType")).isEqualTo("MICROSERVICE");
    }
}
