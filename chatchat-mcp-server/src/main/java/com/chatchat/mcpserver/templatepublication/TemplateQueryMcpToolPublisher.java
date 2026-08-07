package com.chatchat.mcpserver.templatepublication;

import com.chatchat.mcpserver.api.ApiTemplateDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.mcp.McpToolApplicability;
import com.chatchat.mcpserver.ops.CommandTemplateDiscoveryService;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolPublicationReviewer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateQueryMcpToolPublisher {

    public static final String TOOL_NAME = "template_query";

    private final McpSyncServer mcpSyncServer;
    private final TemplateQueryBindingService bindingService;
    private final CommandTemplateDiscoveryService discoveryService;
    private final ApiTemplateDiscoveryMcpToolPublisher apiDiscoveryPublisher;
    private final AgentRuntimeGovernanceFactory governanceFactory;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove();
        McpToolPublicationReviewer.addReviewedTool(mcpSyncServer, specification());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Governed dynamic MCP tool published: {}", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("Authorized template query")
            .description("Read-only discovery of system-maintained templates. The result scope is fixed by "
                + "the authenticated MCP service and caller roles. It only returns templates selected in "
                + "Template Query Publication administration and never returns raw commands, SQL, URLs, headers, "
                + "request bodies, credentials, or other execution specifications.")
            .inputSchema(inputSchema())
            .meta(meta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = query(request.arguments());
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("Authorized template query completed")
                        .structuredContent(result)
                        .isError(false)
                        .build();
                } catch (Exception ex) {
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage() == null ? "Template query failed" : ex.getMessage())
                        .structuredContent(Map.of(
                            "schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION,
                            "success", false,
                            "error", ex.getMessage() == null ? "Template query failed" : ex.getMessage()
                        ))
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    Map<String, Object> query(Map<String, Object> arguments) {
        Map<String, Set<String>> allowed = bindingService.allowedTemplates(McpInvocationContext.current());
        String requestedType = text(arguments == null ? null : arguments.get("assetType"));
        int limit = limit(arguments);
        List<String> assetTypes = requestedType.isBlank()
            ? List.of(TemplateAssetCatalogService.SSH, TemplateAssetCatalogService.SQL,
                TemplateAssetCatalogService.HTTP, TemplateAssetCatalogService.DATABASE_QUERY,
                TemplateAssetCatalogService.API)
            : List.of(requestedType);

        List<Map<String, Object>> templates = new ArrayList<>();
        for (String assetType : assetTypes) {
            Set<String> templateIds = allowed.getOrDefault(assetType, Set.of());
            if (templateIds.isEmpty()) {
                continue;
            }
            Map<String, Object> scopedArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
            scopedArguments.put("assetType", assetType);
            if (TemplateAssetCatalogService.API.equals(assetType)) {
                scopedArguments.put("templateIds", List.copyOf(templateIds));
            } else {
                scopedArguments.put("_authorizedTemplateIds", List.copyOf(templateIds));
            }
            scopedArguments.put("limit", limit);
            Map<String, Object> result = TemplateAssetCatalogService.API.equals(assetType)
                ? apiDiscoveryPublisher.queryAuthorized(scopedArguments, templateIds)
                : discoveryService.query(forceTarget(scopedArguments, assetType));
            Object values = result.get("templates");
            if (values instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    if (value instanceof Map<?, ?> map && templates.size() < limit) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        map.forEach((key, entry) -> item.put(String.valueOf(key), entry));
                        item.putIfAbsent("assetType", assetType);
                        templates.add(item);
                    }
                }
            }
            if (templates.size() >= limit) {
                break;
            }
        }
        McpInvocationContext.Context context = McpInvocationContext.current();
        return Map.of(
            "schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION,
            "success", true,
            "toolName", TOOL_NAME,
            "returnedCount", templates.size(),
            "templates", List.copyOf(templates),
            "publicationScope", Map.of(
                "serviceId", context == null || context.clientId() == null ? "" : context.clientId(),
                "roleBound", context != null && context.roles() != null && !context.roles().isBlank(),
                "configuredAssetTypes", allowed.keySet(),
                "configuredTemplateCount", allowed.values().stream().mapToInt(Set::size).sum()
            ),
            "rawExecutionSpecReturned", false
        );
    }

    private Map<String, Object> forceTarget(Map<String, Object> arguments, String assetType) {
        Map<String, Object> values = new LinkedHashMap<>(arguments);
        String targetKind = switch (assetType) {
            case TemplateAssetCatalogService.SSH -> "host";
            case TemplateAssetCatalogService.SQL -> "database";
            case TemplateAssetCatalogService.HTTP -> "http";
            case TemplateAssetCatalogService.DATABASE_QUERY -> "business_database_query";
            default -> throw new IllegalArgumentException("Unsupported template asset type: " + assetType);
        };
        values.put("finalDecision", targetKind);
        values.put("targetKind", targetKind);
        values.put("confidence", 1.0);
        values.put("candidates", List.of(Map.of("targetKind", targetKind, "confidence", 1.0)));
        return values;
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "assetType", Map.of(
                "type", "string",
                "enum", List.of(TemplateAssetCatalogService.SSH, TemplateAssetCatalogService.SQL,
                    TemplateAssetCatalogService.HTTP, TemplateAssetCatalogService.DATABASE_QUERY,
                    TemplateAssetCatalogService.API),
                "description", "Optional template asset family. Omit to search every family authorized by the fixed publication binding."
            ),
            "filters", Map.of(
                "type", "object",
                "description", "Logical search intent and classification filters only. Raw execution fields are forbidden.",
                "additionalProperties", true
            ),
            "bilingualIntent", Map.of("type", "array", "items", Map.of("type", "string")),
            "intentZh", Map.of("type", "string"),
            "intentEn", Map.of("type", "string"),
            "excludeTemplateIds", Map.of("type", "array", "items", Map.of("type", "string")),
            "trace", Map.of("type", "object", "additionalProperties", true),
            "limit", Map.of("type", "integer", "minimum", 1,
                "maximum", CommandTemplateDiscoveryService.MAX_LIMIT)
        ), List.of(), false, null, null);
    }

    private Map<String, Object> meta() {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "template_discovery");
        governance.put("operation_type", "read");
        governance.put("risk_level", "low");
        governance.put("data_scope", "service_role_template_binding");
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "auto_execute", "allow_user_override", false));
        governance.put("permission", mutableMap(
            "mode", "service_and_role_binding",
            "deny_unbound", true,
            "scope_source", "server_managed_template_query_binding"
        ));
        governance.put("input_policy", mutableMap(
            "allow_raw_command", false,
            "allow_raw_sql", false,
            "allow_raw_http_spec", false,
            "allow_template_scope_override", false
        ));
        governance.put("output_policy", mutableMap(
            "raw_execution_spec", false,
            "only_selected_templates", true,
            "maximum_templates", CommandTemplateDiscoveryService.MAX_LIMIT
        ));
        Map<String, Object> meta = new LinkedHashMap<>(governanceFactory.toMeta(
            "template_query_publication", "system-managed", governance));
        meta.put("schemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION);
        meta.put("kind", "dynamic_authorized_template_discovery");
        meta.put("readOnly", true);
        meta.put("runtimeAction", "read_only");
        meta.put("controlPlane", "server_managed");
        meta.put("governanceEditable", false);
        meta.put("rawExecutionSpecReturned", false);
        meta.put(McpToolApplicability.META_KEY, McpToolApplicability.of(
            "template_query:authorized_discovery",
            "Authorized template discovery",
            List.of("template_discovery", "service_role_scope"),
            "Search only system templates selected for the authenticated service and caller role.",
            List.of("Discover an existing authorized template and its parameter contract before execution."),
            List.of("Executing templates", "Changing governance", "Expanding publication scope")
        ));
        return Map.copyOf(meta);
    }

    private int limit(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("limit");
        try {
            return value == null ? 10 : Math.max(1, Math.min(CommandTemplateDiscoveryService.MAX_LIMIT,
                Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException ex) {
            return 10;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> mutableMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private void remove() {
        try {
            mcpSyncServer.removeTool(TOOL_NAME);
        } catch (Exception ex) {
            log.debug("Dynamic template query tool was not registered: {}", ex.getMessage());
        }
    }
}
