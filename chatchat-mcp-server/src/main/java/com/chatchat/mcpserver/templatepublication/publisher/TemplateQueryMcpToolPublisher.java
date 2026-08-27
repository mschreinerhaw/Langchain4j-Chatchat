package com.chatchat.mcpserver.templatepublication.publisher;

import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateQueryParentCatalog;
import com.chatchat.mcpserver.templatepublication.policy.TemplateQueryBridgeRoutingPolicy;
import com.chatchat.mcpserver.templatepublication.policy.TemplateQueryToolNamePolicy;

import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.mcpserver.api.publication.ApiTemplateDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.mcp.McpToolApplicability;
import com.chatchat.mcpserver.ops.discovery.CommandTemplateDiscoveryService;
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
import java.util.LinkedHashSet;

@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateQueryMcpToolPublisher {

    private static final String LEGACY_TOOL_NAME = "template_query";
    public static final String CHILD_TOOL_ARGUMENT = "_templateQueryChildToolName";

    private final McpSyncServer mcpSyncServer;
    private final TemplateQueryBindingService bindingService;
    private final CommandTemplateDiscoveryService discoveryService;
    private final ApiTemplateDiscoveryMcpToolPublisher apiDiscoveryPublisher;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final Set<String> publishedToolNames = new LinkedHashSet<>();

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        Set<String> namesToRemove = new LinkedHashSet<>(publishedToolNames);
        namesToRemove.add(LEGACY_TOOL_NAME);
        namesToRemove.forEach(this::remove);
        publishedToolNames.clear();
        for (String toolName : bindingService.publishedToolNames()) {
            String reviewedName = TemplateQueryToolNamePolicy.requireToolName(toolName);
            McpToolPublicationReviewer.addReviewedTool(mcpSyncServer, specification(reviewedName));
            publishedToolNames.add(reviewedName);
        }
        mcpSyncServer.notifyToolsListChanged();
        log.info("Governed dynamic template query tools published: {}", publishedToolNames);
    }

    private McpServerFeatures.SyncToolSpecification specification(String toolName) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(toolName)
            .title("Authorized template query")
            .description("Read-only discovery of system-maintained templates. The result scope is fixed by "
                + "the authenticated MCP service and caller roles. It only returns templates selected in "
                + "Template Query Publication administration and never returns raw commands, SQL, URLs, headers, "
                + "request bodies, credentials, or other execution specifications.")
            .inputSchema(inputSchema())
            .meta(meta(toolName))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = query(toolName, request.arguments());
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

    Map<String, Object> query(String toolName, Map<String, Object> arguments) {
        return query(toolName, null, arguments);
    }

    public Map<String, Object> queryFromParent(String toolName, String parentToolName,
                                               Map<String, Object> arguments) {
        return query(toolName, parentToolName, arguments);
    }

    private Map<String, Object> query(String toolName, String invokedParentToolName,
                                      Map<String, Object> arguments) {
        String reviewedName = TemplateQueryToolNamePolicy.requireToolName(toolName);
        String configuredParent = invokedParentToolName == null
            ? null : bindingService.parentToolName(reviewedName);
        if (configuredParent != null && !configuredParent.equals(invokedParentToolName)) {
            throw new IllegalArgumentException("Dynamic template query parent mismatch: " + reviewedName);
        }
        McpInvocationContext.Context invocationContext = McpInvocationContext.current();
        TemplateQueryBindingService.PolicyResolution policy = invocationContext == null
            ? bindingService.resolvePolicy(null, reviewedName, arguments)
            : bindingService.resolvePolicy(invocationContext, reviewedName);
        if (invokedParentToolName != null && !policy.parentToolNames().contains(invokedParentToolName)) {
            log.warn("Dynamic template query authorization rejected tool={} parent={} transportContext={} "
                    + "resolvedParents={} configuredTemplateCount={}",
                reviewedName, invokedParentToolName, invocationContext != null,
                policy.parentToolNames(), policy.configuredTemplateCount());
            throw new IllegalArgumentException("Dynamic template query is not authorized for current caller: "
                + reviewedName);
        }
        Map<String, Set<String>> allowed = policy.allowedTemplates();
        String requestedType = text(arguments == null ? null : arguments.get("assetType"));
        int limit = limit(arguments);
        Set<String> excludedTemplateIds = stringSet(
            arguments == null ? null : arguments.get("excludeTemplateIds"));
        List<String> assetTypes;
        if (invokedParentToolName != null) {
            assetTypes = List.of(parentAssetType(invokedParentToolName));
        } else {
            assetTypes = requestedType.isBlank()
                ? List.of(TemplateAssetCatalogService.SSH, TemplateAssetCatalogService.SQL,
                    TemplateAssetCatalogService.HTTP, TemplateAssetCatalogService.DATABASE_QUERY,
                    TemplateAssetCatalogService.API)
                : List.of(requestedType);
        }

        List<Map<String, Object>> templates = new ArrayList<>();
        int candidateCount = 0;
        int filteredUnauthorizedCount = 0;
        int filteredExcludedCount = 0;
        for (String assetType : assetTypes) {
            Set<String> templateIds = allowed.getOrDefault(assetType, Set.of());
            if (templateIds.isEmpty()) {
                continue;
            }
            Map<String, Object> scopedArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
            scopedArguments.remove(CHILD_TOOL_ARGUMENT);
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
                        candidateCount++;
                        String returnedTemplateId = text(map.get("templateId"));
                        if (returnedTemplateId.isBlank() || !templateIds.contains(returnedTemplateId)) {
                            filteredUnauthorizedCount++;
                            continue;
                        }
                        if (excludedTemplateIds.contains(returnedTemplateId)) {
                            filteredExcludedCount++;
                            continue;
                        }
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
            "toolName", reviewedName,
            "returnedCount", templates.size(),
            "templates", List.copyOf(templates),
            "publicationScope", Map.of(
                "serviceId", context == null || context.clientId() == null || context.clientId().isBlank()
                    ? TemplateQueryParentCatalog.SERVICE_ID : context.clientId(),
                "roleBound", context != null && context.roles() != null && !context.roles().isBlank(),
                "configuredAssetTypes", allowed.keySet(),
                "configuredTemplateCount", policy.configuredTemplateCount(),
                "parentToolNames", policy.parentToolNames(),
                "policyVersion", policy.policyVersion()
            ),
            "filterAudit", Map.of(
                "candidateCount", candidateCount,
                "returnedCount", templates.size(),
                "filteredUnauthorizedCount", filteredUnauthorizedCount,
                "filteredExcludedCount", filteredExcludedCount,
                "policyCacheHit", policy.cacheHit(),
                "policyResolvedAt", policy.resolvedAt().toString()
            ),
            "rawExecutionSpecReturned", false
        );
    }

    public static String childToolName(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get(CHILD_TOOL_ARGUMENT);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String parentAssetType(String parentToolName) {
        return switch (parentToolName) {
            case com.chatchat.mcpserver.ops.discovery.TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME ->
                TemplateAssetCatalogService.SSH;
            case com.chatchat.mcpserver.ops.discovery.TemplateDiscoveryMcpToolPublisher.SQL_DATASOURCE_TEMPLATE_TOOL_NAME ->
                TemplateAssetCatalogService.SQL;
            case com.chatchat.mcpserver.ops.discovery.TemplateDiscoveryMcpToolPublisher.HTTP_ENDPOINT_TEMPLATE_TOOL_NAME ->
                TemplateAssetCatalogService.HTTP;
            case com.chatchat.mcpserver.ops.discovery.TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME ->
                TemplateAssetCatalogService.DATABASE_QUERY;
            case ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME -> TemplateAssetCatalogService.API;
            default -> throw new IllegalArgumentException("Unsupported parent template query tool: " + parentToolName);
        };
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

    private Map<String, Object> meta(String toolName) {
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
        String parentToolName = TemplateQueryBridgeRoutingPolicy.publicBridge(
            bindingService.parentToolName(toolName));
        McpDynamicCapabilityRoute route = McpDynamicCapabilityRoute.parentDelegation(
            parentToolName, CHILD_TOOL_ARGUMENT);
        meta.put(McpDynamicCapabilityRoute.METADATA_KEY, route.toMetadata());
        // Rolling-upgrade fields for API nodes that have not adopted the v1 route contract.
        meta.put("kind", "dynamic_authorized_template_discovery");
        meta.put("parentToolName", parentToolName);
        meta.put("routingMode", "api_parent_mcp_policy_filter");
        meta.put("readOnly", true);
        meta.put("runtimeAction", "read_only");
        meta.put("controlPlane", "server_managed");
        meta.put("governanceEditable", false);
        meta.put("rawExecutionSpecReturned", false);
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, "mcp.authorized-template-query.v1", "filters"));
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

    private Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object item : iterable) {
            String normalized = text(item);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return Set.copyOf(values);
    }

    private Map<String, Object> mutableMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Dynamic template query tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }
}
