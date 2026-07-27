package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.routing.TargetKindRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TemplateDiscoveryMcpToolPublisherTest {

    @Test
    void sshTemplateToolIsTypedReadOnlyDiscoveryTool() throws Exception {
        TemplateDiscoveryMcpToolPublisher publisher = publisher(mock(McpSyncServer.class));
        Method method = TemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "domainTemplateQueryTool", String.class, String.class, String.class,
            String.class, String.class, String.class);
        method.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) method.invoke(
                publisher,
                TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME,
                "SSH command template discovery",
                "Read-only MCP tool for retrieving SSH host command templates only.",
                "ssh_host",
                "host",
                "host command templates"
            );
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> boundary = (Map<?, ?>) meta.get("toolBoundary");
        Map<?, ?> indexPolicy = (Map<?, ?>) meta.get("indexPolicy");
        Map<?, ?> routingProtocol = (Map<?, ?>) meta.get("routingProtocol");

        assertThat(tool.name()).isEqualTo(TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME);
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("assetType")).isEqualTo("ssh_host");
        assertThat(meta.get("rawExecutionSpecReturned")).isEqualTo(false);
        assertThat(boundary.get("rejectCrossTypeRouting")).isEqualTo(true);
        assertThat(indexPolicy.get("logicalIndex")).isEqualTo("template:ssh_host");
        assertThat(((List<?>) routingProtocol.get("allowedFilterFields"))
            .stream().map(String::valueOf).toList())
            .contains("env", "intent", "retrievalsignals")
            .doesNotContain("templateids");
    }

    @Test
    void refreshPublishesOnlyInfrastructureTemplateDiscoveryTools() {
        McpSyncServer server = mock(McpSyncServer.class);
        TemplateDiscoveryMcpToolPublisher publisher = publisher(server);

        publisher.refresh();

        verify(server, times(3)).addTool(any(McpServerFeatures.SyncToolSpecification.class));
        verify(server).notifyToolsListChanged();
    }

    private TemplateDiscoveryMcpToolPublisher publisher(McpSyncServer server) {
        return new TemplateDiscoveryMcpToolPublisher(
            server,
            mock(CommandTemplateDiscoveryService.class),
            new TargetKindRegistry()
        );
    }
}
