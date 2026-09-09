package com.chatchat.mcpserver.templatepublication.publisher;

import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryRouteResolver;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateQueryParentCatalog;
import com.chatchat.mcpserver.templatepublication.policy.TemplateQueryToolNamePolicy;
import com.chatchat.mcpserver.templatepublication.retrieval.BoundTemplateCandidateRetriever;

import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.mcp.capability.McpTemplateSelectionScope;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.mcp.McpToolApplicability;
import com.chatchat.mcpserver.ops.discovery.CommandTemplateDiscoveryService;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class TemplateQueryMcpToolPublisher implements com.chatchat.mcpserver.tool.McpToolContributor {

    private static final String LEGACY_TOOL_NAME = "template_query";
    public static final String CHILD_TOOL_ARGUMENT = "_templateQueryChildToolName";

    private final McpSyncServer mcpSyncServer;
    private final TemplateQueryBindingService bindingService;
    private final TemplateQueryRouteResolver routeResolver;
    private final TemplateAssetCatalogService assetCatalogService;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;
    private final BoundTemplateCandidateRetriever candidateRetriever = new BoundTemplateCandidateRetriever();
    private final Set<String> publishedToolNames = new LinkedHashSet<>();

    public synchronized void refresh() {
        com.chatchat.mcpserver.tool.McpToolPublicationPipeline.PublicationResult result = refreshPublication();
        publishedToolNames.clear();
        publishedToolNames.addAll(result.publishedTools());
        log.info("Governed dynamic template query tools published: {}", publishedToolNames);
    }

    @Override public String contributorId() { return "template_query"; }
    @Override public McpSyncServer publicationServer() { return mcpSyncServer; }
    @Override public List<com.chatchat.mcpserver.tool.ToolPublication> contribute() {
        return bindingService.publishedToolNames().stream()
            .map(TemplateQueryToolNamePolicy::requireToolName)
            .map(this::specification)
            .map(com.chatchat.mcpserver.tool.ToolPublication::from).toList();
    }
    @Override public Set<String> retiredToolNames() {
        java.util.LinkedHashSet<String> retired = new java.util.LinkedHashSet<>(publishedToolNames);
        retired.add(LEGACY_TOOL_NAME);
        return Set.copyOf(retired);
    }

    private McpServerFeatures.SyncToolSpecification specification(String toolName) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(toolName)
            .title("Authorized template query")
            .description("Agent-selectable child capability representing a fixed, system-maintained template set. "
                + "It is never executed directly: Runtime delegates it to the declared parent toolbox, which "
                + "returns this child's bound template information and parameter contracts without another search. "
                + "The result scope is fixed by "
                + "the authenticated MCP service and caller roles. It only returns templates selected in "
                + "Template Query Publication administration and never returns raw commands, SQL, URLs, headers, "
                + "request bodies, credentials, or other execution specifications.")
            .inputSchema(inputSchema())
            .meta(meta(toolName))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> directInvocationRejected(toolName))
            .build();
    }

    private McpSchema.CallToolResult directInvocationRejected(String toolName) {
        String parentToolName = routeResolver.requireRoute(toolName).parentToolName();
        Map<String, Object> evidence = Map.of(
            "schemaVersion", "mcp_child_capability_delegation.v1",
            "success", false,
            "errorCode", "MCP_CHILD_CAPABILITY_REQUIRES_PARENT",
            "errorMessage", "Child capabilities cannot execute directly; invoke the declared parent toolbox",
            "childToolName", toolName,
            "parentToolName", parentToolName,
            "recoveryAction", "INVOKE_DECLARED_PARENT"
        );
        return McpSchema.CallToolResult.builder()
            .addTextContent(String.valueOf(evidence.get("errorMessage")))
            .structuredContent(evidence)
            .isError(true)
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
        TemplateQueryRouteResolver.Route route = routeResolver.requireRoute(reviewedName);
        if (invokedParentToolName != null && !route.parentToolName().equals(invokedParentToolName)) {
            throw new IllegalArgumentException("Dynamic template query parent mismatch: " + reviewedName);
        }
        McpInvocationContext.Context invocationContext = McpInvocationContext.current();
        TemplateQueryBindingService.PolicyResolution policy = invocationContext == null
            ? bindingService.resolvePolicy(null, reviewedName, arguments)
            : bindingService.resolvePolicy(invocationContext, reviewedName);
        if (invokedParentToolName != null && policy.parentToolNames().stream()
            .noneMatch(parent -> parent.equals(invokedParentToolName))) {
            log.warn("Dynamic template query authorization rejected tool={} parent={} transportContext={} "
                    + "resolvedParents={} configuredTemplateCount={}",
                reviewedName, invokedParentToolName, invocationContext != null,
                policy.parentToolNames(), policy.configuredTemplateCount());
            throw new IllegalArgumentException("Dynamic template query is not authorized for current caller: "
                + reviewedName);
        }
        Set<String> templateIds = policy.allowedTemplates().getOrDefault(route.assetType(), Set.of());
        Map<String, TemplateAssetCatalogService.TemplateAsset> enabledAssets = new LinkedHashMap<>();
        assetCatalogService.listEnabled().stream()
            .filter(asset -> route.assetType().equals(asset.assetType()))
            .forEach(asset -> enabledAssets.put(asset.templateId(), asset));
        int limit = recallLimit(arguments);
        BoundTemplateCandidateRetriever.Recall recall = candidateRetriever.recall(
            List.copyOf(enabledAssets.values()), templateIds, arguments, limit, policy.policyVersion());
        List<Map<String, Object>> templates = new ArrayList<>();
        for (TemplateAssetCatalogService.TemplateAsset asset : recall.templates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("templateId", asset.templateId());
            item.put("title", asset.title());
            item.put("description", asset.description());
            item.put("assetType", asset.assetType());
            item.put("category", asset.category());
            item.put("businessCategoryCode", asset.businessCategoryCode());
            item.put("businessCategoryName", asset.businessCategoryName());
            item.put("parameterSchema", asset.parameterSchema());
            item.put("exists", true);
            item.put("selectionSource", "template_query_binding");
            templates.add(Map.copyOf(item));
        }
        // Completeness belongs to the entire persisted binding, never to the current page.
        // Using templates.size() here makes every legitimate paged response look incomplete.
        int unavailableCount = Math.max(0, templateIds.size() - recall.candidateUniverseCount());
        McpInvocationContext.Context context = McpInvocationContext.current();
        return Map.ofEntries(
            Map.entry("schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION),
            Map.entry("resultKind", "RAW_RECORDS"),
            Map.entry("resultSchemaRef", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION),
            Map.entry("resultEntityKind", "template"),
            Map.entry("success", true),
            Map.entry("toolName", reviewedName),
            Map.entry("returnedCount", templates.size()),
            Map.entry("templates", List.copyOf(templates)),
            Map.entry("scopeMode", McpTemplateSelectionScope.FIXED_BINDING),
            Map.entry("selectionMode", McpTemplateSelectionScope.BOUND_SCOPE_RECALL),
            Map.entry("semanticReviewRequired", true),
            Map.entry("globalSearchPerformed", false),
            Map.entry("boundScopeRecallPerformed", true),
            Map.entry("bindingComplete", unavailableCount == 0),
            Map.entry("candidateUniverseCount", recall.candidateUniverseCount()),
            Map.entry("hasMore", recall.hasMore()),
            Map.entry("pagination", mutableMap(
                "offset", recall.offset(),
                "limit", limit,
                "hasMore", recall.hasMore(),
                "nextCursor", recall.nextCursor()
            )),
            Map.entry("publicationScope", Map.of(
                "serviceId", context == null || context.clientId() == null || context.clientId().isBlank()
                    ? TemplateQueryParentCatalog.SERVICE_ID : context.clientId(),
                "roleBound", context != null && context.roles() != null && !context.roles().isBlank(),
                "configuredAssetTypes", policy.allowedTemplates().keySet(),
                "configuredTemplateCount", policy.configuredTemplateCount(),
                "parentToolNames", policy.parentToolNames(),
                "policyVersion", policy.policyVersion()
            )),
            Map.entry("provenance", Map.of(
                "sourceRef", "mcp://" + (context == null || context.clientId() == null
                    || context.clientId().isBlank() ? TemplateQueryParentCatalog.SERVICE_ID : context.clientId())
                    + "/" + reviewedName,
                "dataVersion", policy.policyVersion(),
                "asOf", policy.resolvedAt().toString(),
                "rowRange", Map.of("offset", recall.offset(), "count", templates.size()),
                "filterSummary", Map.of("scopeMode", McpTemplateSelectionScope.FIXED_BINDING,
                    "candidateUniverseCount", recall.candidateUniverseCount())
            )),
            Map.entry("filterAudit", Map.of(
                "candidateCount", policy.configuredTemplateCount(),
                "returnedCount", templates.size(),
                "retrievalSignalCount", recall.retrievalSignalCount(),
                "unavailableOrUnauthorizedCount", unavailableCount,
                "policyCacheHit", policy.cacheHit(),
                "policyResolvedAt", policy.resolvedAt().toString()
            )),
            Map.entry("rawExecutionSpecReturned", false)
        );
    }

    private int recallLimit(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("limit");
        if (raw instanceof Number number) {
            return Math.max(1, Math.min(CommandTemplateDiscoveryService.MAX_LIMIT, number.intValue()));
        }
        return Math.min(20, CommandTemplateDiscoveryService.MAX_LIMIT);
    }

    public static String childToolName(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get(CHILD_TOOL_ARGUMENT);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "assetType", Map.of(
                "type", "string",
                "enum", List.of(TemplateAssetCatalogService.SSH, TemplateAssetCatalogService.SQL,
                    TemplateAssetCatalogService.HTTP, TemplateAssetCatalogService.DATABASE_QUERY,
                    TemplateAssetCatalogService.API, TemplateAssetCatalogService.PYTHON),
                "description", "Compatibility hint only; the persisted child-parent binding determines the asset family."
            ),
            "filters", Map.of(
                "type", "object",
                "description", "Optional recall signals used only to rank candidates inside the child's fixed template set.",
                "additionalProperties", true
            ),
            "bilingualIntent", Map.of("type", "array", "items", Map.of("type", "string")),
            "intentZh", Map.of("type", "string"),
            "intentEn", Map.of("type", "string"),
            "trace", Map.of("type", "object", "additionalProperties", true),
            "limit", Map.of("type", "integer", "minimum", 1,
                "maximum", CommandTemplateDiscoveryService.MAX_LIMIT),
            "cursor", Map.of("type", "string",
                "description", "Opaque cursor returned by the previous bound-scope recall page.")
        ), List.of(), false, null, null);
    }

    private Map<String, Object> meta(String toolName) {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "template_discovery");
        governance.put("operation_type", "read");
        governance.put("runtime_level", "discovery");
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
        meta.put("resultKind", "RAW_RECORDS");
        meta.put("resultSchemaRef", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION);
        meta.put("resultEntityKind", "template");
        // The child remains the Agent-visible capability. Transport routing is declared as
        // control-plane metadata so Runtime can invoke the stable parent gateway without
        // leaking this internal discriminator into either public input schema.
        TemplateQueryRouteResolver.Route persistedRoute = routeResolver.requireRoute(toolName);
        String persistedParentToolName = persistedRoute.parentToolName();
        meta.put(McpDynamicCapabilityRoute.METADATA_KEY,
            McpDynamicCapabilityRoute.parentDelegation(persistedParentToolName, CHILD_TOOL_ARGUMENT).toMetadata());
        meta.put(McpTemplateSelectionScope.METADATA_KEY,
            McpTemplateSelectionScope.fixedBinding(persistedRoute.assetType()).toMetadata());
        meta.put("scopeMode", McpTemplateSelectionScope.FIXED_BINDING);
        meta.put("selectionMode", McpTemplateSelectionScope.BOUND_SCOPE_RECALL);
        meta.put("assetType", persistedRoute.assetType());
        meta.put("routingMode", McpDynamicCapabilityRoute.ROUTING_MODE_PARENT_DELEGATION);
        meta.put("readOnly", true);
        meta.put("runtimeAction", "read_only");
        meta.put("controlPlane", "server_managed");
        meta.put("governanceEditable", false);
        meta.put("rawExecutionSpecReturned", false);
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(toolName, "discovery"));
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, "mcp.authorized-template-query.v1", "filters", "template"));
        meta.put(McpToolApplicability.META_KEY, McpToolApplicability.of(
            "template_query:authorized_discovery",
            "Authorized template discovery",
            List.of("template_discovery", "service_role_scope"),
            "Recall relevant candidates only inside the fixed templates bound to the authenticated service and caller role.",
            List.of("Resolve a bounded page of bound templates and their current parameter contracts for Runtime review."),
            List.of("Executing templates", "Changing governance", "Expanding publication scope")
        ));
        return Map.copyOf(meta);
    }

    private Map<String, Object> mutableMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

}
