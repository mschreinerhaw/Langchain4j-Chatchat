package com.chatchat.integration.mcp.service.routing;

import com.chatchat.integration.mcp.service.routing.DynamicMcpToolRouteService;
import com.chatchat.integration.mcp.service.transport.McpGatewayClient;
import com.chatchat.integration.mcp.service.routing.McpInvocationArgumentAdapter;
import com.chatchat.integration.mcp.service.config.McpServiceConfigService;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import com.chatchat.common.mcp.contract.McpToolGovernance;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.mcp.capability.McpCapabilityNode;
import com.chatchat.common.mcp.capability.McpCapabilityNodeKind;
import com.chatchat.common.mcp.capability.McpCapabilityFallbackPolicy;
import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.McpToolNamePolicy;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowContractCatalog;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronizes enabled MCP services into ToolRegistry as dynamic tools.
 */
@Slf4j
@Service
public class McpToolRegistryBridge {

    private final ToolRegistry toolRegistry;
    private final McpServiceConfigService configService;
    private final McpGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;
    private final DynamicMcpToolRouteService routeService;
    private final ToolWorkflowContractCatalog contractCatalog;
    private final McpInvocationArgumentAdapter invocationArgumentAdapter =
        new McpInvocationArgumentAdapter();

    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();
    private final Map<String, RegisteredMcpTool> registeredTools = new ConcurrentHashMap<>();
    private final Map<String, String> registeredContractChecksums = new ConcurrentHashMap<>();
    private final AtomicLong toolsChangeGeneration = new AtomicLong();
    private final AtomicBoolean toolsChangeRefreshRunning = new AtomicBoolean();

    public McpToolRegistryBridge(ToolRegistry toolRegistry,
                                 McpServiceConfigService configService,
                                 McpGatewayClient gatewayClient,
                                 ObjectMapper objectMapper,
                                 DynamicMcpToolRouteService routeService) {
        this(toolRegistry, configService, gatewayClient, objectMapper, routeService,
            (ToolWorkflowContractCatalog) null);
    }

    @Autowired
    public McpToolRegistryBridge(ToolRegistry toolRegistry,
                                 McpServiceConfigService configService,
                                 McpGatewayClient gatewayClient,
                                 ObjectMapper objectMapper,
                                 DynamicMcpToolRouteService routeService,
                                 ObjectProvider<ToolWorkflowContractCatalog> catalogProvider) {
        this(toolRegistry, configService, gatewayClient, objectMapper, routeService,
            catalogProvider.getIfAvailable());
    }

    private McpToolRegistryBridge(ToolRegistry toolRegistry,
                                  McpServiceConfigService configService,
                                  McpGatewayClient gatewayClient,
                                  ObjectMapper objectMapper,
                                  DynamicMcpToolRouteService routeService,
                                  ToolWorkflowContractCatalog contractCatalog) {
        this.toolRegistry = toolRegistry;
        this.configService = configService;
        this.gatewayClient = gatewayClient;
        this.objectMapper = objectMapper;
        this.routeService = routeService;
        this.contractCatalog = contractCatalog;
        this.gatewayClient.addToolsChangeListener(this::onToolsChanged);
    }

    private void onToolsChanged(String serviceId) {
        toolsChangeGeneration.incrementAndGet();
        log.info("Runtime OS received MCP tool catalog change serviceId={}", serviceId);
        drainToolsChangeRefreshes();
    }

    private void drainToolsChangeRefreshes() {
        if (!toolsChangeRefreshRunning.compareAndSet(false, true)) return;
        long handledGeneration = -1L;
        try {
            do {
                handledGeneration = toolsChangeGeneration.get();
                refreshRegistry();
            } while (toolsChangeGeneration.get() != handledGeneration);
        } finally {
            toolsChangeRefreshRunning.set(false);
            if (toolsChangeGeneration.get() != handledGeneration) drainToolsChangeRefreshes();
        }
    }

    /**
     * Performs the refresh registry operation.
     */
    public synchronized void refreshRegistry() {
        refreshRegistry(0);
    }

    /**
     * Refreshes the registry with an optional discovery timeout override.
     */
    public synchronized void refreshRegistry(int discoveryTimeoutMs) {
        List<McpServiceConfig> services = configService.listEnabled();
        if (services.isEmpty()) {
            managedToolNames.forEach(toolRegistry::unregisterTool);
            managedToolNames.clear();
            registeredTools.clear();
            registeredContractChecksums.clear();
            routeService.clear();
            log.info("No enabled MCP service found, skip MCP tool registration");
            return;
        }

        Set<String> discoveredNames = new LinkedHashSet<>();
        Set<String> refreshedServiceIds = new LinkedHashSet<>();
        Set<String> enabledServiceIds = new LinkedHashSet<>();
        for (McpServiceConfig service : services) {
            enabledServiceIds.add(service.getId());
            try {
                List<McpToolDefinition> tools = gatewayClient.discoverTools(
                    service, Math.max(0, discoveryTimeoutMs));
                if (tools.isEmpty()) {
                    refreshedServiceIds.add(service.getId());
                    log.info("No MCP tools discovered for service {}", service.getName());
                    continue;
                }
                for (McpToolDefinition definition : tools) {
                    try {
                        String registered = registerSingleTool(service, definition);
                        if (registered != null) discoveredNames.add(registered);
                    } catch (IllegalArgumentException ex) {
                        log.warn("Skip invalid MCP tool route serviceId={} toolName={}: {}",
                            service.getId(), definition == null ? null : definition.name(), ex.getMessage());
                    }
                }
                refreshedServiceIds.add(service.getId());
            } catch (Exception ex) {
                log.warn("Skip MCP service {} (id={}) during refresh: {}",
                    service.getName(), service.getId(), ex.getMessage());
            }
        }
        Set<String> stale = new LinkedHashSet<>(managedToolNames);
        stale.removeAll(discoveredNames);
        stale.removeIf(name -> {
            RegisteredMcpTool prior = registeredTools.get(name);
            return prior == null || (!enabledServiceIds.contains(prior.serviceId())
                || refreshedServiceIds.contains(prior.serviceId()));
        });
        // stale now contains tools belonging to failed services. Preserve them only while
        // the database still declares the exact registered contract ACTIVE. A publication
        // that races with a discovery outage must fail closed instead of serving an old contract.
        Set<String> preserved = stale;
        preserved.removeIf(name -> !registeredContractStillActive(name));
        Set<String> removable = new LinkedHashSet<>(managedToolNames);
        removable.removeAll(discoveredNames);
        removable.removeAll(preserved);
        removable.forEach(name -> {
            RegisteredMcpTool prior = registeredTools.remove(name);
            registeredContractChecksums.remove(name);
            toolRegistry.unregisterTool(name);
            if (prior != null) routeService.unregister(prior.serviceId(), prior.remoteToolName());
            managedToolNames.remove(name);
        });
        log.info("MCP tool registry refreshed, registered {} tools", managedToolNames.size());
    }

    /**
     * Lists the registered tools.
     *
     * @return the registered tools list
     */
    public List<RegisteredMcpTool> listRegisteredTools() {
        return registeredTools.values().stream()
            .map(this::withInferredCapabilityKind)
            .sorted(Comparator.comparing(RegisteredMcpTool::localToolName))
            .toList();
    }

    private RegisteredMcpTool withInferredCapabilityKind(RegisteredMcpTool tool) {
        McpCapabilityNode node = tool.capabilityNode();
        if (node == null || node.child() || node.abstractCapability()) return tool;
        String parentSemantic = McpToolNamePolicy.workflowSemanticKey(tool.remoteToolName());
        boolean hasImplementation = registeredTools.values().stream()
            .filter(candidate -> candidate != tool)
            .filter(candidate -> tool.serviceId().equals(candidate.serviceId()))
            .map(RegisteredMcpTool::capabilityNode)
            .filter(Objects::nonNull)
            .map(McpCapabilityNode::parentToolName)
            .filter(Objects::nonNull)
            .map(McpToolNamePolicy::workflowSemanticKey)
            .anyMatch(parentSemantic::equals);
        if (!hasImplementation) return tool;
        McpCapabilityNode abstractNode = new McpCapabilityNode(
            node.serviceId(), node.toolName(), null,
            McpCapabilityNodeKind.ABSTRACT_CAPABILITY,
            node.fallbackPolicy(),
            node.relationType(), node.routingMode(), node.attributes());
        return new RegisteredMcpTool(
            tool.localToolName(), tool.serviceId(), tool.serviceName(), tool.remoteToolName(),
            tool.description(), tool.backendServiceType(), tool.category(), tool.categories(),
            tool.tags(), tool.applicability(), abstractNode);
    }

    /**
     * Performs the discover tools operation.
     *
     * @param serviceId the service id value
     * @return the operation result
     */
    public List<McpToolDefinition> discoverTools(String serviceId) {
        McpServiceConfig config = configService.getById(serviceId);
        return gatewayClient.discoverTools(config);
    }

    /**
     * Performs the invoke operation.
     *
     * @param serviceId the service id value
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @return the operation result
     */
    @Deprecated(forRemoval = true)
    McpToolInvokeResult invoke(String serviceId, String toolName, Map<String, Object> arguments) {
        McpServiceConfig config = configService.getById(serviceId);
        DynamicMcpToolRouteService.InvocationPlan plan =
            routeService.plan(serviceId, toolName, arguments);
        return gatewayClient.invokeTool(config, plan.remoteToolName(), plan.arguments());
    }

    /** Executes a canonical kernel call through the registered contract adapter. */
    public McpToolInvokeResult invoke(McpServiceCall call) {
        RegisteredMcpTool registered = registeredTools.values().stream()
            .filter(tool -> tool.serviceId().equals(call.serviceId()))
            .filter(tool -> tool.localToolName().equals(call.toolName())
                || tool.remoteToolName().equals(call.toolName()))
            .findFirst()
            .orElse(null);
        if (registered == null) {
            return McpToolInvokeResult.failure("MCP tool is not registered in the active runtime snapshot",
                "MCP_TOOL_NOT_FOUND", true, "REFRESH_OR_DISCOVER");
        }
        Map<String, Object> context = new LinkedHashMap<>(call.context());
        ToolInput input = ToolInput.builder()
            .parameters(new LinkedHashMap<>(call.arguments()))
            .requestId(call.requestId())
            .userId(stringValue(context.get("userId")))
            .conversationId(stringValue(context.get("conversationId")))
            .context(context)
            .build();
        ToolOutput output = toolRegistry.executeEnhancedTool(registered.localToolName(), input);
        if (output == null) {
            return McpToolInvokeResult.failure("MCP registered tool returned no output",
                "MCP_EMPTY_TOOL_OUTPUT", true, "RETRY_OR_REPAIR");
        }
        Map<String, Object> metadata = output.getMetadata() == null ? Map.of() : output.getMetadata();
        Object rawData = metadata.get("mcpRawData");
        Map<String, Object> executionState = mapValue(metadata.get("executionState"));
        String action = firstText(stringValue(metadata.get("mcpAction")), stringValue(metadata.get("action")));
        boolean retryable = Boolean.TRUE.equals(firstPresent(metadata.get("mcpRetryable"), metadata.get("retryable")));
        return new McpToolInvokeResult(output.isSuccess(), output.getData(), rawData, output.getMessage(),
            output.getErrorMessage(), output.getExceptionType(), retryable, action, executionState);
    }

    /**
     * Registers the single tool.
     *
     * @param service the service value
     * @param definition the definition value
     */
    private String registerSingleTool(McpServiceConfig service, McpToolDefinition definition) {
        String localName = toLocalToolName(service.getName(), definition.name());
        String candidate = localName;
        int suffix = 2;
        while (toolRegistry.hasTool(candidate) && !managedToolNames.contains(candidate)) {
            candidate = localName + "_" + suffix;
            suffix += 1;
        }
        localName = candidate;

        Map<String, Object> discoveredInputSource = definition.inputSchema() == null
            ? Map.of() : definition.inputSchema();
        Map<String, Object> discoveredOutputSource = outputSchema(definition.meta());
        Map<String, Object> discoveredInput = canonicalObjectSchema(discoveredInputSource);
        Map<String, Object> discoveredOutput = canonicalObjectSchema(discoveredOutputSource);
        ToolWorkflowContractSnapshot activeContract = null;
        if (contractCatalog != null) {
            activeContract = contractCatalog.synchronizeDiscovery(
                service.getId(), service.getName(), localName, definition.name(),
                definition.description(), discoveredInput, discoveredOutput, definition.meta(),
                service.isContractAutoPublish())
                .orElse(null);
            if (activeContract == null) {
                log.info("MCP tool staged as DRAFT and excluded from runtime registry: {}", localName);
                return null;
            }
        }
        Map<String, Object> selectedInput = activeContract == null
            ? discoveredInput : activeContract.inputSchema();
        Map<String, Object> selectedOutput = activeContract == null
            ? discoveredOutput : activeContract.outputSchema();
        Map<String, Object> runtimeInput = canonicalObjectSchema(selectedInput);
        Map<String, Object> runtimeOutput = canonicalObjectSchema(selectedOutput);
        Map<String, Object> effectiveMeta = effectiveRuntimeMetadata(
            definition.meta(), activeContract);
        McpToolDefinition runtimeDefinition = withRuntimeContract(
            definition, runtimeInput, runtimeOutput, effectiveMeta);
        try {
            runtimeDefinition.validateContract();
        } catch (IllegalArgumentException invalidContract) {
            log.warn("MCP tool excluded because its unified contract is invalid: serviceId={} tool={} error={}",
                service.getId(), definition.name(), invalidContract.getMessage());
            return null;
        }
        McpToolGovernance governance = runtimeDefinition.governance();

        Map<String, Object> extraMetadata = new LinkedHashMap<>();
        extraMetadata.put("serviceId", service.getId());
        extraMetadata.put("remoteToolName", definition.name());
        extraMetadata.put("inputSchema", ToolCallBatchSchema.augment(
            definition.name(),
            runtimeInput
        ));
        extraMetadata.put("outputSchema", runtimeDefinition.outputSchema());
        extraMetadata.put("contractVersion", runtimeDefinition.contractVersion());
        extraMetadata.put("governance", governance);
        extraMetadata.put("inputSchemaSource", schemaSource(activeContract,
            activeContract == null ? discoveredInputSource : selectedInput));
        extraMetadata.put("outputSchemaSource", schemaSource(activeContract,
            activeContract == null ? discoveredOutputSource : selectedOutput));
        if (activeContract != null) {
            extraMetadata.put(ToolWorkflowContract.METADATA_KEY, activeContract.asMetadata());
            extraMetadata.put("workflowContractVersion", activeContract.version());
            extraMetadata.put("workflowContractChecksum", activeContract.checksum());
        }
        if (effectiveMeta != null && !effectiveMeta.isEmpty()) {
            extraMetadata.put("mcpToolMeta", effectiveMeta);
            copyToolResultInstruction(extraMetadata, effectiveMeta);
        }
        // The ACTIVE database snapshot wins over newly discovered schemas.
        if (!runtimeOutput.isEmpty()) extraMetadata.put("toolResultSchema", runtimeOutput);
        if (definition.timeoutMillis() != null) {
            extraMetadata.put("remoteTimeoutMs", definition.timeoutMillis());
        }
        DynamicMcpToolRouteService.RouteDefinition route =
            routeService.register(service.getId(), runtimeDefinition).orElse(null);
        if (route != null) {
            extraMetadata.put("parentRemoteToolName", route.parentToolName());
            extraMetadata.put("routingMode", route.routingMode());
            extraMetadata.put(McpDynamicCapabilityRoute.METADATA_KEY,
                new McpDynamicCapabilityRoute(
                    route.contractVersion(), route.parentToolName(),
                    route.implementationIdentityArgument(), route.routingMode(), Map.of())
                    .toMetadata());
        }
        McpCapabilityNodeKind declaredKind = McpCapabilityNodeKind.parse(
            effectiveMeta == null ? null : effectiveMeta.get("nodeKind"),
            McpCapabilityNodeKind.STANDALONE);
        McpCapabilityFallbackPolicy declaredFallback = McpCapabilityFallbackPolicy.parse(
            effectiveMeta == null ? null : effectiveMeta.get("fallbackPolicy"),
            declaredKind == McpCapabilityNodeKind.ABSTRACT_CAPABILITY
                ? McpCapabilityFallbackPolicy.DENY_WHEN_NO_IMPLEMENTATION
                : McpCapabilityFallbackPolicy.ALLOW_STANDALONE);
        McpCapabilityNode capabilityNode = new McpCapabilityNode(
            service.getId(), localName,
            route == null ? null : route.parentToolName(),
            route == null ? declaredKind
                : McpCapabilityNodeKind.BUSINESS_IMPLEMENTATION,
            route == null ? declaredFallback : McpCapabilityFallbackPolicy.ALLOW_STANDALONE,
            route == null ? McpCapabilityNode.RELATION_ROOT
                : McpCapabilityNode.RELATION_IMPLEMENTS_ABSTRACT_CAPABILITY,
            route == null ? null : route.routingMode(),
            route == null
                ? Map.of("remoteToolName", definition.name())
                : Map.of(
                    "remoteToolName", definition.name(),
                    "routeContractVersion", route.contractVersion(),
                    "implementationIdentityArgument", route.implementationIdentityArgument())
        );
        extraMetadata.put(McpCapabilityHierarchy.METADATA_KEY, capabilityNode.toMetadata());

        String category = firstText(definition.category(), "mcp_external");
        List<String> categories = distinctStrings(List.of("mcp", "external", category));
        List<String> tags = new ArrayList<>(List.of("mcp", sanitize(service.getName())));
        tags.addAll(stringList(effectiveMeta == null ? null : effectiveMeta.get("tags")));
        tags = distinctStrings(tags);
        Map<String, Object> applicability = applicability(runtimeDefinition);

        ToolMetadata metadata = ToolMetadata.builder()
            .id(localName)
            .title(definition.name())
            .description(definition.description())
            .version("1.0.0")
            .author("MCP:" + service.getName())
            .categories(categories)
            .category(category)
            .riskLevel(governance.riskLevel())
            .operationType(governance.operationType())
            .runtimeLevel(governance.runtimeLevel())
            .userVisible(definition.userVisible() == null || definition.userVisible())
            .confirmation(emptyToNull(runtimeDefinition.confirmation()))
            .permissions(emptyToNull(governance.permissions()))
            .inputPolicy(emptyToNull(governance.inputPolicy()))
            .outputPolicy(emptyToNull(governance.outputPolicy()))
            .outputType("json")
            .timeoutMillis(definition.timeoutMillis())
            .agentCompatible(true)
            .parameters(toolParameters(runtimeInput))
            .tags(tags)
            .metadata(extraMetadata)
            .build();

        ToolRegistry.EnhancedTool tool = new McpEnhancedTool(
            service.getId(),
            definition.name(),
            route,
            metadata,
            runtimeInput);
        toolRegistry.registerTool(localName, metadata, tool);
        managedToolNames.add(localName);
        registeredTools.put(localName, new RegisteredMcpTool(
            localName,
            service.getId(),
            service.getName(),
            definition.name(),
            definition.description(),
            backendServiceType(runtimeDefinition),
            category,
            categories,
            tags,
            applicability,
            capabilityNode
        ));
        if (activeContract != null) {
            registeredContractChecksums.put(localName, activeContract.checksum());
        } else {
            registeredContractChecksums.remove(localName);
        }
        return localName;
    }

    private boolean registeredContractStillActive(String localName) {
        if (contractCatalog == null) {
            return true;
        }
        RegisteredMcpTool registered = registeredTools.get(localName);
        String registeredChecksum = registeredContractChecksums.get(localName);
        if (registered == null || registeredChecksum == null || registeredChecksum.isBlank()) {
            return false;
        }
        try {
            return contractCatalog.findActive(
                    registered.serviceId(), registered.localToolName(), registered.remoteToolName())
                .map(active -> registeredChecksum.equals(active.checksum()))
                .orElse(false);
        } catch (RuntimeException ex) {
            log.warn("Unable to verify ACTIVE MCP contract for {}, preserving current runtime snapshot: {}",
                localName, ex.getMessage());
            return true;
        }
    }

    private Map<String, Object> outputSchema(Map<String, Object> meta) {
        if (meta == null) return Map.of();
        for (String key : List.of("outputSchema", "resultSchema", "output_schema", "result_schema")) {
            Map<String, Object> candidate = mapValue(meta.get(key));
            if (!candidate.isEmpty()) return candidate;
        }
        return Map.of();
    }

    /**
     * Projects incomplete provider schemas into the Runtime OS object protocol without
     * inventing business fields. The source marker keeps the normalization auditable.
     */
    private Map<String, Object> canonicalObjectSchema(Map<String, Object> schema) {
        Map<String, Object> normalized = new LinkedHashMap<>(schema == null ? Map.of() : schema);
        normalized.putIfAbsent("type", "object");
        normalized.putIfAbsent("additionalProperties", true);
        return Map.copyOf(normalized);
    }

    private String schemaSource(ToolWorkflowContractSnapshot activeContract, Map<String, Object> selectedSchema) {
        if (selectedSchema == null || selectedSchema.isEmpty() || selectedSchema.get("type") == null) {
            return "runtime_adapter_default";
        }
        return activeContract == null ? "remote_discovery" : "active_contract";
    }

    private McpToolDefinition withRuntimeContract(McpToolDefinition definition,
                                                  Map<String, Object> inputSchema,
                                                  Map<String, Object> outputSchema,
                                                  Map<String, Object> metadata) {
        Map<String, Object> contractMetadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        contractMetadata.put("outputSchema", outputSchema == null ? Map.of() : outputSchema);
        return new McpToolDefinition(
            definition.name(), definition.description(), inputSchema,
            definition.category(), definition.riskLevel(), definition.operationType(),
            definition.runtimeLevel(), definition.userVisible(), definition.confirmation(),
            definition.permissions(), definition.inputPolicy(), definition.outputPolicy(),
            definition.timeoutMillis(), Map.copyOf(contractMetadata));
    }

    /**
     * The ACTIVE catalog owns schemas and its explicitly published metadata, but
     * it must not erase live transport/capability declarations that are outside
     * the workflow-contract snapshot (for example parent delegation routes).
     * Catalog extensions win on key conflicts.
     */
    private Map<String, Object> effectiveRuntimeMetadata(
        Map<String, Object> discoveredMetadata,
        ToolWorkflowContractSnapshot activeContract
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(
            discoveredMetadata == null ? Map.of() : discoveredMetadata);
        if (activeContract != null && activeContract.extensions() != null) {
            merged.putAll(activeContract.extensions());
        }
        return Map.copyOf(merged);
    }

    /**
     * Preserves the MCP JSON Schema contract in the local tool registry. Runtime
     * guards rely on this metadata to reject dependent calls whose required
     * inputs have not yet been produced by an upstream step.
     */
    private List<ToolParameter> toolParameters(Map<String, Object> inputSchema) {
        Map<String, Object> schema = inputSchema == null ? Map.of() : inputSchema;
        Map<String, Object> properties = mapValue(schema.get("properties"));
        Set<String> required = new LinkedHashSet<>(stringList(schema.get("required")));
        if (properties.isEmpty()) {
            return List.of(ToolParameter.builder()
                .name("query")
                .type("string")
                .description("Natural language query for MCP tool input")
                .required(false)
                .build());
        }
        List<ToolParameter> parameters = new ArrayList<>();
        properties.forEach((name, rawProperty) -> {
            Map<String, Object> property = mapValue(rawProperty);
            parameters.add(ToolParameter.builder()
                .name(name)
                .type(firstText(stringValue(property.get("type")), "object"))
                .description(stringValue(property.get("description")))
                .required(required.contains(name))
                .defaultValue(property.get("default"))
                .enumValues(stringList(property.get("enum")).toArray(String[]::new))
                .metadata(property)
                .build());
        });
        return List.copyOf(parameters);
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                values.put(String.valueOf(key), item);
            }
        });
        return values;
    }

    private Map<String, Object> applicability(McpToolDefinition definition) {
        if (definition == null || definition.meta() == null) {
            return Map.of();
        }
        Object value = definition.meta().get("applicability");
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return Map.copyOf(result);
    }

    /**
     * Reads the backend service type declared by the MCP tool itself. No tool-name inference is used.
     */
    private String backendServiceType(McpToolDefinition definition) {
        if (definition == null || definition.meta() == null || definition.meta().isEmpty()) {
            return null;
        }
        Map<String, Object> meta = definition.meta();
        Map<String, Object> applicability = applicability(definition);
        List<String> declaredTypes = stringList(applicability.get("backendServiceTypes"));
        Object declared = firstPresent(
            declaredTypes.isEmpty() ? null : declaredTypes.get(0),
            meta.get("backendServiceType"),
            meta.get("backend_service_type"),
            meta.get("serviceType"),
            meta.get("service_type"),
            meta.get("resourceType"),
            meta.get("resource_type"),
            meta.get("assetType"),
            meta.get("asset_type")
        );
        return declared == null || String.valueOf(declared).isBlank()
            ? null
            : String.valueOf(declared).trim();
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (!String.valueOf(value).isBlank()) {
            result.add(String.valueOf(value).trim());
        }
        return List.copyOf(result);
    }

    private List<String> distinctStrings(Iterable<String> values) {
        Set<String> distinct = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    distinct.add(value.trim());
                }
            }
        }
        return List.copyOf(distinct);
    }

    /**
     * Converts the value to local tool name.
     *
     * @param serviceName the service name value
     * @param toolName the tool name value
     * @return the converted local tool name
     */
    private String toLocalToolName(String serviceName, String toolName) {
        return "mcp_" + sanitize(serviceName) + "_" + sanitize(toolName);
    }

    /**
     * Performs the sanitize operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "tool";
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "tool" : normalized;
    }

    /**
     * Performs the first text operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Performs the empty to null operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private Map<String, Object> emptyToNull(Map<String, Object> value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private void copyToolResultInstruction(Map<String, Object> target, Map<String, Object> meta) {
        if (target == null || meta == null || meta.isEmpty()) {
            return;
        }
        Object instruction = firstPresent(
            meta.get("toolResultInstruction"),
            meta.get("tool_result_instruction"),
            meta.get("resultInstruction"),
            meta.get("result_instruction")
        );
        if (instruction != null && !String.valueOf(instruction).isBlank()) {
            target.put("toolResultInstruction", instruction);
        }
        Object resultSchema = firstPresent(meta.get("resultSchema"), meta.get("result_schema"), meta.get("outputSchema"));
        if (resultSchema != null) {
            target.put("toolResultSchema", resultSchema);
        }
    }

    private class McpEnhancedTool implements ToolRegistry.EnhancedTool {

        private final String serviceId;
        private final String requestedToolName;
        private final DynamicMcpToolRouteService.RouteDefinition route;
        private final ToolMetadata metadata;
        private final Map<String, Object> inputSchema;

        /**
         * Creates a new McpToolRegistryBridge instance.
         *
         * @param serviceId the service id value
         * @param requestedToolName the Agent-visible tool name
         * @param metadata the metadata value
         */
        private McpEnhancedTool(String serviceId, String requestedToolName,
                                DynamicMcpToolRouteService.RouteDefinition route,
                                ToolMetadata metadata,
                                Map<String, Object> inputSchema) {
            this.serviceId = serviceId;
            this.requestedToolName = requestedToolName;
            this.route = route;
            this.metadata = metadata;
            this.inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            return metadata;
        }

        /**
         * Executes the execute.
         *
         * @param input the input value
         * @return the operation result
         */
        @Override
        public ToolOutput execute(ToolInput input) {
            McpCapabilityNode declaredNode = McpCapabilityNode.fromMetadata(
                mapValue(metadata.getMetadata() == null ? null
                    : metadata.getMetadata().get(McpCapabilityHierarchy.METADATA_KEY)),
                serviceId, metadata.getId()).orElse(null);
            if (route == null && declaredNode != null && declaredNode.abstractCapability()
                && declaredNode.fallbackPolicy()
                == McpCapabilityFallbackPolicy.DENY_WHEN_NO_IMPLEMENTATION) {
                boolean implementationAvailable = routeService.hasImplementation(
                    serviceId, requestedToolName);
                String code = implementationAvailable
                    ? "MCP_ABSTRACT_CAPABILITY_REQUIRES_IMPLEMENTATION"
                    : "MCP_CAPABILITY_IMPLEMENTATION_UNAVAILABLE";
                ToolOutput failure = ToolOutput.failure(implementationAvailable
                    ? "Abstract MCP capability must be invoked through a concrete business implementation"
                    : "Abstract MCP capability has no available business implementation");
                failure.setExceptionType(code);
                failure.setMetadata(new LinkedHashMap<>(Map.of(
                    "mcpErrorCode", code,
                    "capabilityNode", declaredNode.toMetadata(),
                    "implementationAvailable", implementationAvailable
                )));
                return failure;
            }
            Map<String, Object> semanticArguments = new LinkedHashMap<>();
            if (input.getParameters() != null) {
                semanticArguments.putAll(input.getParameters());
            }
            if (semanticArguments.isEmpty() && input.getRawInput() != null && !input.getRawInput().isBlank()) {
                semanticArguments.put("query", input.getRawInput());
            }

            // Adapt every MCP invocation against the remote tool's original contract at
            // the bridge boundary. Agent orchestration envelopes are useful locally, but
            // must never be validated or transported as remote business parameters.
            // Using the unaugmented schema is important: the Agent-facing batch anyOf
            // contract intentionally has no top-level properties.
            ToolArgumentCompiler.CompilationResult compilation = invocationArgumentAdapter.adapt(
                semanticArguments,
                inputSchema
            );
            if (!compilation.valid()) {
                ToolOutput failure = ToolOutput.failure(
                    compilation.structuredError(metadata.getId(), "mcp_bridge_adapt"));
                failure.setExceptionType("INVALID_TOOL_ARGUMENTS");
                return failure;
            }
            Map<String, Object> arguments = new LinkedHashMap<>(compilation.parameters());
            if (!compilation.repairs().isEmpty() && input.getContext() != null) {
                input.getContext().put("mcpInvocationArgumentRepairs", compilation.repairs());
            }
            enrichInvocationContext(arguments, input);
            DynamicMcpToolRouteService.InvocationPlan plan = route == null
                ? routeService.plan(serviceId, requestedToolName, arguments)
                : routeService.plan(route, arguments);
            long startedAt = System.currentTimeMillis();
            log.info("MCP bridge tool call started localTool={} serviceId={} remoteTool={} requestId={} timeoutMs={} args={}",
                metadata.getId(),
                serviceId,
                plan.remoteToolName(),
                input.getRequestId(),
                metadata.getTimeoutMillis() == null || metadata.getTimeoutMillis() <= 0
                    ? "unbounded" : metadata.getTimeoutMillis(),
                ToolLogSummarizer.summarize(plan.arguments()));
            McpToolInvokeResult result = gatewayClient.invokeTool(
                configService.getById(serviceId),
                plan.remoteToolName(),
                plan.arguments(),
                metadata.getTimeoutMillis()
            );
            if (!result.success()) {
                log.warn("MCP bridge tool call failed localTool={} serviceId={} remoteTool={} requestId={} durationMs={} errorCode={} action={} retryable={} error={} executionState={} result={}",
                    metadata.getId(),
                    serviceId,
                    plan.remoteToolName(),
                    input.getRequestId(),
                    Math.max(0L, System.currentTimeMillis() - startedAt),
                    result.errorCode(),
                    result.action(),
                    result.retryable(),
                    result.errorMessage(),
                    result.executionState(),
                    ToolLogSummarizer.summarizeResult(plan.remoteToolName(), result.data()));
                return failureOutput(result, metadata);
            }
            log.info("MCP bridge tool call succeeded localTool={} serviceId={} remoteTool={} requestId={} durationMs={} message={} result={}",
                metadata.getId(),
                serviceId,
                plan.remoteToolName(),
                input.getRequestId(),
                Math.max(0L, System.currentTimeMillis() - startedAt),
                result.message(),
                ToolLogSummarizer.summarizeResult(plan.remoteToolName(), result.data()));
            ToolOutput output = ToolOutput.success(result.data(), result.message() == null ? "MCP call success" : result.message());
            if (output.getMetadata() == null) {
                output.setMetadata(new LinkedHashMap<>());
            }
            copyToolResultInstruction(output.getMetadata(), metadata.getMetadata());
            enrichOutputMetadata(output, result);
            return output;
        }
    }

    private ToolOutput failureOutput(McpToolInvokeResult result, ToolMetadata metadata) {
        String errorMessage = result == null || result.errorMessage() == null
            ? "MCP tool call failed"
            : result.errorMessage();
        ToolOutput output = ToolOutput.failure(errorMessage);
        String errorCode = result == null ? null : result.errorCode();
        output.setExceptionType(firstText(errorCode, "MCP_TOOL_CALL_FAILED"));
        enrichOutputMetadata(output, result);
        output.getMetadata().put("errorCode", firstText(errorCode, "MCP_TOOL_CALL_FAILED"));
        output.getMetadata().put("retryable", result != null && result.retryable());
        output.getMetadata().put("action", result == null ? "STOP" : firstText(result.action(), "STOP"));
        if (safeDiscoveryTimeout(metadata, result)) {
            output.getMetadata().put("retryable", true);
            output.getMetadata().put("mcpRetryable", true);
            output.getMetadata().put("action", "RETRY");
            output.getMetadata().put("mcpAction", "RETRY");
            output.getMetadata().put("retryReason", "READ_ONLY_DISCOVERY_TIMEOUT");
        }
        return output;
    }

    /**
     * Timeouts are retryable only when the publisher-owned workflow contract
     * proves that the operation is discovery-only.  Execution tools deliberately
     * remain non-retryable because their remote side effect may be ambiguous.
     */
    private boolean safeDiscoveryTimeout(ToolMetadata metadata, McpToolInvokeResult result) {
        if (result == null || !"MCP_TOOL_TIMEOUT".equalsIgnoreCase(result.errorCode())) {
            return false;
        }
        return ToolWorkflowContract.declaredRole(metadata)
            .map(role -> role == ToolWorkflowRole.ASSET_DISCOVERY
                || role == ToolWorkflowRole.TEMPLATE_DISCOVERY)
            .orElse(false);
    }

    private void enrichOutputMetadata(ToolOutput output, McpToolInvokeResult result) {
        if (output == null) {
            return;
        }
        if (output.getMetadata() == null) {
            output.setMetadata(new LinkedHashMap<>());
        }
        if (result == null) {
            return;
        }
        if (result.executionState() != null && !result.executionState().isEmpty()) {
            output.getMetadata().put("executionState", result.executionState());
            output.getMetadata().put("executionStateName", result.executionState().get("state"));
            output.getMetadata().put("executionRetryCount", result.executionState().get("retryCount"));
        }
        output.getMetadata().put("mcpAction", result.action());
        output.getMetadata().put("mcpRetryable", result.retryable());
        output.getMetadata().put("mcpRawData", result.rawData());
    }

    @SuppressWarnings("unchecked")
    private void enrichInvocationContext(Map<String, Object> arguments, ToolInput input) {
        if (arguments == null) {
            return;
        }
        Map<String, Object> inputContext = input == null || input.getContext() == null ? Map.of() : input.getContext();
        String tenantId = firstText(
            stringValue(arguments.get("tenantId")),
            stringValue(arguments.get("tenant_id")),
            stringValue(inputContext.get("tenantId")),
            stringValue(inputContext.get("tenant_id")),
            stringValue(inputContext.get("tenant"))
        );
        String userId = firstText(
            stringValue(arguments.get("userId")),
            stringValue(arguments.get("user_id")),
            stringValue(inputContext.get("userId")),
            stringValue(inputContext.get("user_id")),
            input == null ? null : input.getUserId(),
            "anonymous"
        );
        String username = firstText(
            stringValue(arguments.get("username")),
            stringValue(inputContext.get("username")),
            stringValue(inputContext.get("userName"))
        );
        boolean canonicalRolesResolved = Boolean.TRUE.equals(inputContext.get("canonicalRolesResolved"));
        String canonicalRoles = firstText(
            roleValue(inputContext.get("roles")),
            roleValue(inputContext.get("roleIds"))
        );
        String roles = canonicalRolesResolved ? canonicalRoles : firstText(
            roleValue(arguments.get("roles")),
            roleValue(arguments.get("roleIds")),
            canonicalRoles
        );
        String requestId = firstText(
            stringValue(arguments.get("requestId")),
            stringValue(arguments.get("request_id")),
            stringValue(inputContext.get("requestId")),
            input == null ? null : input.getRequestId()
        );
        String conversationId = firstText(
            stringValue(arguments.get("conversationId")),
            stringValue(arguments.get("conversation_id")),
            stringValue(inputContext.get("conversationId")),
            input == null ? null : input.getConversationId()
        );

        if (tenantId != null) {
            arguments.putIfAbsent("tenantId", tenantId);
        }
        arguments.putIfAbsent("userId", userId);
        if (username != null) {
            arguments.putIfAbsent("username", username);
        }
        if (canonicalRolesResolved) {
            arguments.remove("roleIds");
            if (roles == null) {
                arguments.remove("roles");
            } else {
                arguments.put("roles", roles);
            }
        } else if (roles != null) {
            arguments.putIfAbsent("roles", roles);
        }
        if (requestId != null) {
            arguments.putIfAbsent("requestId", requestId);
        }
        if (conversationId != null) {
            arguments.putIfAbsent("conversationId", conversationId);
        }
        copyContextMap(arguments, inputContext, "defaultDataAsset");
        copyContextMap(arguments, inputContext, "assetSelectionPolicy");
        copyContextMap(arguments, inputContext, "mcpExecutionContext");

        Map<String, Object> mcpContext = arguments.get("mcpContext") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        Map<String, Object> tenant = mcpContext.get("tenant") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        if (tenantId != null) {
            tenant.putIfAbsent("tenantId", tenantId);
            mcpContext.put("tenant", tenant);
            mcpContext.putIfAbsent("tenantId", tenantId);
        }
        mcpContext.putIfAbsent("userId", userId);
        if (username != null) {
            mcpContext.putIfAbsent("username", username);
        }
        if (canonicalRolesResolved) {
            mcpContext.remove("roleIds");
            if (roles == null) {
                mcpContext.remove("roles");
            } else {
                mcpContext.put("roles", roles);
            }
        } else if (roles != null) {
            mcpContext.putIfAbsent("roles", roles);
        }
        if (requestId != null) {
            mcpContext.putIfAbsent("traceId", requestId);
        }
        if (conversationId != null) {
            mcpContext.putIfAbsent("conversationId", conversationId);
        }
        // Runtime intent is transport metadata, not a model/tool parameter. Preserve the
        // small allow-listed value across the MCP boundary so the remote provider can
        // select the safe execution path (for example, a recall-only final-summary pass).
        String internalPurpose = firstText(stringValue(inputContext.get("internalPurpose")));
        if (internalPurpose != null) {
            mcpContext.putIfAbsent("internalPurpose", internalPurpose);
        }
        arguments.put("mcpContext", mcpContext);
    }

    @SuppressWarnings("unchecked")
    private void copyContextMap(Map<String, Object> arguments, Map<String, Object> inputContext, String key) {
        if (arguments == null || inputContext == null || key == null || key.isBlank() || arguments.containsKey(key)) {
            return;
        }
        Object value = inputContext.get(key);
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            arguments.put(key, new LinkedHashMap<>((Map<String, Object>) map));
        }
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String roleValue(Object value) {
        if (value instanceof Iterable<?> values) {
            List<String> roles = new ArrayList<>();
            for (Object item : values) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    roles.add(String.valueOf(item).trim());
                }
            }
            return roles.isEmpty() ? null : String.join(",", roles);
        }
        return stringValue(value);
    }

    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record RegisteredMcpTool(
        String localToolName,
        String serviceId,
        String serviceName,
        String remoteToolName,
        String description,
        String backendServiceType,
        String category,
        List<String> categories,
        List<String> tags,
        Map<String, Object> applicability,
        McpCapabilityNode capabilityNode
    ) {
        public RegisteredMcpTool(String localToolName,
                                 String serviceId,
                                 String serviceName,
                                 String remoteToolName,
                                 String description) {
            this(localToolName, serviceId, serviceName, remoteToolName, description, null,
                null, List.of(), List.of(), Map.of(), null);
        }

        public RegisteredMcpTool(String localToolName,
                                 String serviceId,
                                 String serviceName,
                                 String remoteToolName,
                                 String description,
                                 String backendServiceType) {
            this(localToolName, serviceId, serviceName, remoteToolName, description, backendServiceType,
                null, List.of(), List.of(), Map.of(), null);
        }

        public RegisteredMcpTool {
            categories = categories == null ? List.of() : List.copyOf(categories);
            tags = tags == null ? List.of() : List.copyOf(tags);
            applicability = applicability == null ? Map.of() : Map.copyOf(applicability);
        }
    }

    /**
     * Performs the serialize object operation.
     *
     * @param object the object value
     * @return the operation result
     */
    public String serializeObject(Object object) {
        if (object == null) {
            return "";
        }
        if (object instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            return String.valueOf(object);
        }
    }

    /**
     * Performs the argument keys operation.
     *
     * @param arguments the arguments value
     * @return the operation result
     */
    private List<String> argumentKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        return arguments.keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .sorted()
            .toList();
    }
}
