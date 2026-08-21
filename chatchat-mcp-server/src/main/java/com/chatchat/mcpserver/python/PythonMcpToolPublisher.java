package com.chatchat.mcpserver.python;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.McpToolPublicationReviewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonMcpToolPublisher {
    public static final String ANALYSIS_RUN_TOOL = "python_analysis_run";
    static final List<String> LEGACY_PROTOCOL_TOOLS = List.of(
        "python_asset_query", "python_template_query", "python_data_file_query", "python_template_execute");

    private final ObjectProvider<McpSyncServer> serverProvider;
    private final PythonTemplateAssetRepository templates;
    private final PythonAnalysisBridge bridge;
    private final McpToolConcurrencyManager concurrencyManager;
    private final ObjectMapper objectMapper;
    private final Set<String> managed = ConcurrentHashMap.newKeySet();

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { refresh(); }

    public synchronized void refresh() {
        McpSyncServer server = serverProvider.getIfAvailable();
        if (server == null) return;
        Set<String> obsolete = new LinkedHashSet<>(managed);
        obsolete.addAll(LEGACY_PROTOCOL_TOOLS);
        templates.findByStatus("PUBLISHED").stream().map(PythonTemplate::getToolName)
            .filter(name -> name != null && !name.isBlank()).forEach(obsolete::add);
        obsolete.forEach(name -> { try { server.removeTool(name); } catch (Exception ignored) { } });
        managed.clear();
        McpToolPublicationReviewer.addReviewedTool(server, analysisRunSpec());
        managed.add(ANALYSIS_RUN_TOOL);
        server.notifyToolsListChanged();
        log.info("Unified Python MCP bridge published: {}; legacy protocol tools removed", ANALYSIS_RUN_TOOL);
    }

    private McpServerFeatures.SyncToolSpecification analysisRunSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "The user's complete analysis request"));
        properties.put("script", Map.of("type", "string", "description", "Published script filename or template name"));
        properties.put("file", Map.of("type", "string", "description", "User-visible filename or opaque fileId"));
        properties.put("templateId", Map.of("type", "string", "description", "Optional exact template selection after clarification"));
        properties.put("assetId", Map.of("type", "string", "description", "Optional exact Python asset selection"));
        properties.put("environmentId", Map.of("type", "string", "description", "Optional exact environment selection"));
        properties.put("parameters", Map.of("type", "object", "additionalProperties", true,
            "description", "Business parameters; supplied values override defaults"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(ANALYSIS_RUN_TOOL)
            .title("Python 数据分析")
            .description("统一解析当前用户的 Python 脚本、执行环境、数据文件和运行参数，并执行已发布模板。存在歧义时返回候选项，不猜测用户选择。")
            .inputSchema(new McpSchema.JsonSchema("object", properties, List.of(), false, null, null))
            .meta(meta()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            return concurrencyManager.execute(ANALYSIS_RUN_TOOL, "python", arguments,
                () -> callResult(bridge.run(arguments)));
        }).build();
    }

    private Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "python_analysis_run.v1");
        meta.put("assetType", "python_runtime");
        meta.put("runtime_action", "execute");
        meta.put("runtimeAction", "execute");
        meta.put("templateGoverned", true);
        meta.put("bridgeManaged", true);
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(ANALYSIS_RUN_TOOL, "python"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, ToolProtocolDriverContract.of(
            "mcp.python-analysis-bridge.v1",
            List.of(
                "Call python_analysis_run once with the user's complete intent, mentioned script, file and business parameters.",
                "The bridge selects the tenant-scoped published template, its bound environment and the current user's file; never call internal discovery steps.",
                "If requiresClarification is true, ask the user to choose from the returned candidates and call this same tool again with the selected id.",
                "Supplied parameter values override schema defaults; omitted values remain available for script code-level defaults."),
            List.of(
                "Preserve explicit templateId, assetId and environmentId selections during retry.",
                "Never invent a host path, container path, fileId, templateId or environmentId.",
                "Never rewrite a governed template into raw source or bypass the Python bridge.")));
        return Map.copyOf(meta);
    }

    private McpSchema.CallToolResult callResult(PythonAnalysisBridge.Result result) {
        Map<String, Object> structured = result.body();
        String text;
        try { text = objectMapper.writeValueAsString(structured); }
        catch (Exception ex) { text = String.valueOf(structured); }
        return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured)
            .isError(result.error()).build();
    }
}
