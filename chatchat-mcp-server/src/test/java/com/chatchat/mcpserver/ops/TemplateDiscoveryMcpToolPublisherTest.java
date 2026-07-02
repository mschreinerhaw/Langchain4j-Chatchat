package com.chatchat.mcpserver.ops;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TemplateDiscoveryMcpToolPublisherTest {

    @Test
    void sshTemplateToolIsTypedReadOnlyDiscoveryTool() throws Exception {
        TemplateDiscoveryMcpToolPublisher publisher = new TemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(CommandTemplateDiscoveryService.class)
        );
        Method method = TemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "domainTemplateQueryTool", String.class, String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec = (McpServerFeatures.SyncToolSpecification) method.invoke(
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

        assertThat(tool.name()).isEqualTo(TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME);
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("readOnly")).isEqualTo(true);
        assertThat(meta.get("riskLevel")).isEqualTo("low");
        assertThat(meta.get("targetKind")).isEqualTo("host");
        assertThat(meta.get("assetType")).isEqualTo("ssh_host");
        assertThat(meta.get("rawExecutionSpecReturned")).isEqualTo(false);
        assertThat(boundary.get("rejectCrossTypeRouting")).isEqualTo(true);
        assertThat(indexPolicy.get("logicalIndex")).isEqualTo("template:ssh_host");
        assertThat(tool.inputSchema().toString()).contains("bilingualIntent", "intentZh", "intentEn");
    }

    @Test
    void databaseQueryTemplateToolIsNarrowReadOnlyDiscoveryTool() throws Exception {
        TemplateDiscoveryMcpToolPublisher publisher = new TemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(CommandTemplateDiscoveryService.class)
        );
        Method databaseQueryTemplateTool = TemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod("databaseQueryTemplateQueryTool");
        databaseQueryTemplateTool.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) databaseQueryTemplateTool.invoke(publisher);
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> routingProtocol = (Map<?, ?>) meta.get("routingProtocol");

        assertThat(tool.name()).isEqualTo(TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME);
        assertThat(tool.description()).contains("business database query templates only");
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("readOnly")).isEqualTo(true);
        assertThat(meta.get("riskLevel")).isEqualTo("low");
        assertThat(meta.get("targetKind")).isEqualTo("business_database_query");
        assertThat(meta.get("assetType")).isEqualTo("database_query");
        assertThat(meta.get("rawExecutionSpecReturned")).isEqualTo(false);
        assertThat(routingProtocol.get("forcedTargetKind")).isEqualTo("business_database_query");
        assertThat(routingProtocol.get("forcedAssetType")).isEqualTo("database_query");
        assertThat(tool.inputSchema().toString()).contains("bilingualIntent", "intentZh", "intentEn");
    }

    @Test
    void typedTemplateArgumentsForceAssetTypeAndTargetKind() throws Exception {
        TemplateDiscoveryMcpToolPublisher publisher = new TemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(CommandTemplateDiscoveryService.class)
        );
        Method argumentsMethod = TemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "forcedTemplateArguments", Map.class, String.class, String.class, String.class);
        argumentsMethod.setAccessible(true);

        Map<?, ?> arguments = (Map<?, ?>) argumentsMethod.invoke(publisher, Map.of(
            "assetType", "ssh_host",
            "finalDecision", "host",
            "filters", Map.of("intent", "统计每日订单")
        ), TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME, "database_query", "business_database_query");

        assertThat(arguments.get("assetType")).isEqualTo("database_query");
        assertThat(arguments.get("finalDecision")).isEqualTo("business_database_query");
        assertThat(arguments.get("confidence")).isEqualTo(1.0);
        assertThat(arguments.get("filters").toString()).contains("统计每日订单");
        assertThat(arguments.get("candidates").toString()).contains("business_database_query");
    }

    @Test
    void databaseQueryTemplateArgumentsOverrideMismatchedDatabaseCandidate() throws Exception {
        TemplateDiscoveryMcpToolPublisher publisher = new TemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(CommandTemplateDiscoveryService.class)
        );
        Method argumentsMethod = TemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod("databaseQueryTemplateArguments", Map.class);
        argumentsMethod.setAccessible(true);

        Map<?, ?> arguments = (Map<?, ?>) argumentsMethod.invoke(publisher, Map.of(
            "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.95)),
            "finalDecision", "database",
            "filters", Map.of("intent", "分析行情数据发生较大波动时异常提醒数据"),
            "trace", Map.of("plannerVersion", "v1.1")
        ));

        assertThat(arguments.get("assetType")).isEqualTo("database_query");
        assertThat(arguments.get("finalDecision")).isEqualTo("business_database_query");
        assertThat((List<?>) arguments.get("candidates"))
            .singleElement()
            .satisfies(candidate -> {
                Map<?, ?> candidateMap = (Map<?, ?>) candidate;
                assertThat(candidateMap.get("targetKind")).isEqualTo("business_database_query");
                assertThat(candidateMap.get("confidence")).isEqualTo(1.0);
            });
        assertThat(arguments.get("trace").toString())
            .contains("forcedTargetKind=business_database_query")
            .contains("originalCandidates");
    }
}
