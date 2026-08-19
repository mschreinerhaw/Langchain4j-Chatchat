package com.chatchat.mcpserver.python;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.McpToolPublicationReviewer;
import com.fasterxml.jackson.core.type.TypeReference;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonMcpToolPublisher {
    public static final String ASSET_QUERY_TOOL = "python_asset_query";
    public static final String TEMPLATE_QUERY_TOOL = "python_template_query";
    public static final String TEMPLATE_EXECUTE_TOOL = "python_template_execute";

    private final ObjectProvider<McpSyncServer> serverProvider;
    private final PythonTemplateAssetRepository templates;
    private final PythonEnvironmentRepository environments;
    private final ObjectProvider<PythonCapabilityService> serviceProvider;
    private final PythonTemplateArgumentResolver argumentResolver;
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
        templates.findByStatus("PUBLISHED").stream().map(PythonTemplate::getToolName)
            .filter(name -> name != null && !name.isBlank()).forEach(obsolete::add);
        obsolete.addAll(List.of(ASSET_QUERY_TOOL, TEMPLATE_QUERY_TOOL, TEMPLATE_EXECUTE_TOOL));
        obsolete.forEach(name -> { try { server.removeTool(name); } catch (Exception ignored) { } });
        managed.clear();
        add(server, assetQuerySpec());
        add(server, templateQuerySpec());
        add(server, templateExecuteSpec());
        server.notifyToolsListChanged();
        log.info("Python MCP protocol published: {} -> {} -> {}; per-template tools disabled",
            ASSET_QUERY_TOOL, TEMPLATE_QUERY_TOOL, TEMPLATE_EXECUTE_TOOL);
    }

    private void add(McpSyncServer server, McpServerFeatures.SyncToolSpecification specification) {
        McpToolPublicationReviewer.addReviewedTool(server, specification);
        managed.add(specification.tool().name());
    }

    private McpServerFeatures.SyncToolSpecification assetQuerySpec() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(ASSET_QUERY_TOOL)
            .title("Python runtime asset discovery")
            .description("Discover tenant-scoped logical Python runtime assets. This step never executes a script.")
            .inputSchema(querySchema())
            .meta(protocolMeta("python_asset_query.v1", "discover_asset"))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> arguments = safe(request.arguments());
            String tenantId = tenantId(arguments);
            List<PythonTemplate> published = templates.findByTenantIdAndStatus(tenantId, "PUBLISHED");
            Map<String, List<PythonTemplate>> grouped = new LinkedHashMap<>();
            published.forEach(template -> grouped.computeIfAbsent(template.getAssetId(), ignored -> new ArrayList<>()).add(template));
            List<Map<String, Object>> assets = grouped.values().stream().map(this::assetView).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", "python_asset_query_ir.v1");
            result.put("success", true);
            result.put("tenantScoped", true);
            result.put("returnedCount", assets.size());
            result.put("assets", assets);
            result.put("nextTool", TEMPLATE_QUERY_TOOL);
            return callResult(result, false);
        }).build();
    }

    private McpServerFeatures.SyncToolSpecification templateQuerySpec() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(TEMPLATE_QUERY_TOOL)
            .title("Python template discovery")
            .description("Discover published templates under a selected tenant Python asset. Returns execution contracts, never source code.")
            .inputSchema(querySchema())
            .meta(protocolMeta("python_template_query.v1", "discover_template"))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> arguments = safe(request.arguments());
            String tenantId = tenantId(arguments);
            Map<String, Object> filters = map(arguments.get("filters"));
            String assetId = firstText(text(arguments.get("assetId")), text(filters.get("assetId")));
            String query = firstText(text(arguments.get("query")), text(filters.get("query")),
                text(filters.get("intent")), text(filters.get("goal")));
            int limit = Math.max(1, Math.min(integer(arguments.get("limit"), 20), 100));
            List<Map<String, Object>> candidates = templates.findByTenantIdAndStatus(tenantId, "PUBLISHED").stream()
                .filter(template -> assetId == null || assetId.equals(template.getAssetId()))
                .filter(template -> matches(template, query))
                .sorted(Comparator.comparing(PythonTemplate::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).map(this::templateView).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", "python_template_query_ir.v1");
            result.put("success", true);
            result.put("tenantScoped", true);
            result.put("returnedCount", candidates.size());
            result.put("templates", candidates);
            result.put("executionTool", TEMPLATE_EXECUTE_TOOL);
            return callResult(result, false);
        }).build();
    }

    private McpServerFeatures.SyncToolSpecification templateExecuteSpec() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(TEMPLATE_EXECUTE_TOOL)
            .title("Python template execution gateway")
            .description("Execute one published template selected by python_template_query. Business values belong under parameters.")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "templateId", Map.of("type", "string", "description", "templateId returned by python_template_query"),
                "parameters", Map.of("type", "object", "additionalProperties", true),
                "purpose", Map.of("type", "string")
            ), List.of("templateId", "parameters"), false, null, null))
            .meta(executeMeta()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> arguments = safe(request.arguments());
            String tenantId = tenantId(arguments);
            String templateId = requiredText(arguments.get("templateId"), "templateId");
            PythonTemplate template = templates.findByIdAndTenantId(templateId, tenantId)
                .filter(value -> "PUBLISHED".equals(value.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("Python template not found in the current tenant or disabled"));
            Map<String, Object> parameters = argumentResolver.resolve(template.getInputSchemaJson(), map(arguments.get("parameters")));
            return concurrencyManager.execute(TEMPLATE_EXECUTE_TOOL, "python", arguments, () -> {
                PythonExecution execution = serviceProvider.getObject().executeTemplateForTenant(template.getId(), tenantId, parameters);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("schemaVersion", "python_template_execute_result.v1");
                result.put("templateId", template.getId());
                result.put("assetId", template.getAssetId());
                result.put("executionId", execution.getId());
                result.put("status", execution.getStatus());
                result.put("stdout", execution.getStdout());
                result.put("stderr", execution.getStderr());
                result.put("exitCode", execution.getExitCode());
                result.put("durationMs", execution.getDurationMs());
                return callResult(result, execution.getExitCode() == null || execution.getExitCode() != 0);
            });
        }).build();
    }

    private Map<String, Object> assetView(List<PythonTemplate> values) {
        PythonTemplate first = values.get(0);
        PythonEnvironment environment = environments.findById(first.getEnvironmentId()).orElse(null);
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetId", first.getAssetId());
        asset.put("name", environment == null ? "Python runtime asset" : environment.getName());
        asset.put("description", environment == null ? "Tenant Python runtime" : nullable(environment.getDescription()));
        asset.put("assetType", "python_runtime");
        asset.put("environmentId", first.getEnvironmentId());
        asset.put("templateCount", values.size());
        asset.put("capabilities", Map.of("allowedTemplateIds", values.stream().map(PythonTemplate::getId).toList()));
        asset.put("routing", Map.of("nextTool", TEMPLATE_QUERY_TOOL, "assetId", first.getAssetId()));
        return Map.copyOf(asset);
    }

    private Map<String, Object> templateView(PythonTemplate template) {
        Map<String, Object> schema = argumentResolver.schema(template.getInputSchemaJson());
        List<String> required = schema.get("required") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaVersion", "python_command_template.v1");
        view.put("templateId", template.getId());
        view.put("title", template.getTemplateName());
        view.put("description", template.getDescription());
        view.put("scenario", template.getScenario());
        view.put("domain", nullable(template.getDomain()));
        view.put("version", template.getVersion());
        view.put("assetId", template.getAssetId());
        view.put("environmentId", template.getEnvironmentId());
        view.put("parameterSchema", schema);
        view.put("outputSchema", argumentResolver.schema(template.getOutputSchemaJson()));
        view.put("requiredParameters", required);
        view.put("parameterContract", Map.of(
            "executionTool", TEMPLATE_EXECUTE_TOOL,
            "argumentContainer", TEMPLATE_EXECUTE_TOOL + ".parameters",
            "mustPassUnderParameters", true,
            "defaultPolicy", "provided values override defaults; omitted values retain template defaults"
        ));
        view.put("routing", Map.of("callTool", TEMPLATE_EXECUTE_TOOL, "templateId", template.getId()));
        return Map.copyOf(view);
    }

    private McpSchema.JsonSchema querySchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "query", Map.of("type", "string"),
            "assetId", Map.of("type", "string"),
            "filters", Map.of("type", "object", "additionalProperties", true),
            "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)
        ), List.of(), false, null, null);
    }

    private Map<String, Object> protocolMeta(String schemaVersion, String action) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", schemaVersion);
        meta.put("assetType", "python_runtime");
        meta.put("runtime_action", action);
        meta.put("runtimeAction", action);
        meta.put("templateGoverned", true);
        String toolName = switch (action) {
            case "discover_asset" -> ASSET_QUERY_TOOL;
            case "discover_template" -> TEMPLATE_QUERY_TOOL;
            default -> TEMPLATE_EXECUTE_TOOL;
        };
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(toolName, "python"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, driver());
        return Map.copyOf(meta);
    }

    private Map<String, Object> executeMeta() {
        Map<String, Object> meta = new LinkedHashMap<>(protocolMeta("python_template_execute.v1", "execute"));
        meta.put("templateDiscoveryTool", TEMPLATE_QUERY_TOOL);
        return Map.copyOf(meta);
    }

    private Map<String, Object> driver() {
        return ToolProtocolDriverContract.of("mcp.python-template.v1", List.of(
            "Discover a tenant Python asset, discover a published template under that asset, then execute only the selected templateId through python_template_execute.",
            "Pass business inputs only under parameters. Supplied values override schema defaults; omitted values retain defaults.",
            "Template discovery is routing evidence, not execution evidence; never invent a direct per-template tool name."
        ), List.of(
            "Preserve the selected assetId and templateId across repair.",
            "Only a required template parameter without a usable default may block execution.",
            "Never rewrite a governed Python template into raw source, container, image, file path, or direct tool invocation."
        ));
    }

    private boolean matches(PythonTemplate template, String query) {
        if (query == null || query.isBlank()) return true;
        String haystack = String.join(" ", nullable(template.getTemplateName()), nullable(template.getScenario()),
            nullable(template.getDescription()), nullable(template.getKeywords()), nullable(template.getDomain())).toLowerCase(Locale.ROOT);
        return List.of(query.toLowerCase(Locale.ROOT).split("[\\s,;，；]+" )).stream()
            .filter(term -> !term.isBlank()).anyMatch(haystack::contains);
    }

    private String tenantId(Map<String, Object> arguments) {
        Map<String, Object> context = map(arguments.get("mcpContext"));
        Map<String, Object> tenant = map(context.get("tenant"));
        String value = firstText(text(arguments.get("tenantId")), text(context.get("tenantId")), text(tenant.get("tenantId")));
        if (value == null) throw new IllegalArgumentException("tenantId is required for Python asset governance");
        return value;
    }

    private McpSchema.CallToolResult callResult(Map<String, Object> structured, boolean error) {
        String text;
        try { text = objectMapper.writeValueAsString(structured); } catch (Exception ex) { text = String.valueOf(structured); }
        return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(error).build();
    }

    private Map<String, Object> safe(Map<String, Object> value) { return value == null ? Map.of() : value; }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? objectMapper.convertValue(value, new TypeReference<>() {}) : Map.of(); }
    private int integer(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private String requiredText(Object value, String name) { String text = text(value); if (text == null) throw new IllegalArgumentException(name + " is required"); return text; }
    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private String firstText(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return null; }
    private String nullable(String value) { return value == null ? "" : value; }
}
