package com.chatchat.mcpserver.python;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
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
    public static final String ANALYSIS_RUN_TOOL = "python_analysis_query";
    public static final String TEMPLATE_EXECUTE_TOOL = "python_template_execute";
    static final List<String> LEGACY_PROTOCOL_TOOLS = List.of(
        "python_asset_query", "python_template_query", "python_data_file_query", "python_analysis_run");

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
        McpToolPublicationReviewer.addReviewedTool(server, templateExecuteSpec());
        managed.add(TEMPLATE_EXECUTE_TOOL);
        server.notifyToolsListChanged();
        log.info("Unified Python discovery bridge published: {}; Runtime executor retained: {}",
            ANALYSIS_RUN_TOOL, TEMPLATE_EXECUTE_TOOL);
    }

    private McpServerFeatures.SyncToolSpecification analysisRunSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
            "type", "string",
            "description", "The user's complete analysis request",
            "aliases", List.of("query_intent", "intent")));
        properties.put("script", Map.of(
            "type", "string",
            "description", "Published script filename or template name",
            "aliases", List.of("script_name", "scriptFileName")));
        properties.put("file", Map.of(
            "type", "string",
            "description", "User-visible filename or opaque fileId",
            "aliases", List.of("file_target", "file_name", "fileName")));
        properties.put("templateId", Map.of("type", "string", "description", "Optional exact template selection after clarification"));
        properties.put("assetId", Map.of("type", "string", "description", "Optional exact Python asset selection"));
        properties.put("environmentId", Map.of("type", "string", "description", "Optional exact environment selection"));
        properties.put("parameters", Map.of("type", "object", "additionalProperties", true,
            "description", "Business parameters; supplied values override defaults"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(ANALYSIS_RUN_TOOL)
            .title("Python analysis capability query")
            .description("Discover Python script templates, execution environments, and user data bindings available to the current tenant. This tool does not execute scripts. After the model selects a template, python_template_execute runs it through the Agent Runtime governance pipeline.")
            .inputSchema(new McpSchema.JsonSchema("object", properties, List.of(), false, null, null))
            .meta(meta()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            return concurrencyManager.execute(ANALYSIS_RUN_TOOL, "python", arguments,
                () -> callResult(bridge.run(arguments)));
        }).build();
    }

    private McpServerFeatures.SyncToolSpecification templateExecuteSpec() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TEMPLATE_EXECUTE_TOOL)
            .title("Python 模板执行")
            .description("Execute one published Python template selected from python_analysis_query. Multiple accepted templates use Agent Runtime's ordered batch envelope.")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "templateId", Map.of("type", "string"),
                "parameters", Map.of("type", "object", "additionalProperties", true),
                "purpose", Map.of("type", "string")
            ), List.of("templateId", "parameters"), false, null, null))
            .meta(executeMeta()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            return concurrencyManager.execute(TEMPLATE_EXECUTE_TOOL, "python", arguments,
                () -> callResult(bridge.execute(arguments)));
        }).build();
    }

    private Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "python_analysis_query.v1");
        meta.put("assetType", "python_runtime");
        meta.put("runtime_action", "read_only");
        meta.put("runtimeAction", "read_only");
        meta.put("templateGoverned", true);
        meta.put("bridgeManaged", true);
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(ANALYSIS_RUN_TOOL, "python"));
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, "mcp.python-template.v1", "intent+filters"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, ToolProtocolDriverContract.of(
            "mcp.python-analysis-bridge.v1",
            List.of(
                "Call python_analysis_query with the user's complete intent and review every returned template candidate.",
                "Call python_analysis_query again with an accepted templateId only when file binding must be resolved into executionArguments.",
                "Execute accepted templates through python_template_execute; use Agent Runtime's ordered batch envelope for multiple templates.",
                "Supplied parameter values override schema defaults; omitted values remain available for script code-level defaults."),
            List.of(
                "Preserve explicit templateId, assetId and environmentId selections during retry.",
                "Never invent a host path, container path, fileId, templateId or environmentId.",
                "Never rewrite a governed template into raw source or bypass the Python bridge.")));
        return Map.copyOf(meta);
    }

    private Map<String, Object> executeMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "python_template_execute.v1");
        meta.put("assetType", "python_runtime");
        meta.put("runtime_action", "execute");
        meta.put("runtimeAction", "execute");
        meta.put("templateGoverned", true);
        meta.put("templateDiscoveryTool", ANALYSIS_RUN_TOOL);
        meta.put("capabilities", List.of("template_execution", "batch_execution"));
        meta.put("template_execution", true);
        meta.put("batch_execution", true);
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(TEMPLATE_EXECUTE_TOOL, "python"));
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_EXECUTION, "mcp.python-template.v1", "templateId+parameters"));
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
