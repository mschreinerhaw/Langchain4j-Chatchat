package com.chatchat.mcpserver.api.publication;

import com.chatchat.mcpserver.api.invocation.ApiInvokeResult;
import com.chatchat.mcpserver.api.invocation.ApiInvokeService;
import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;

import com.chatchat.common.bridge.AbstractRuntimeBridge;
import com.chatchat.common.bridge.BridgeContract;
import com.chatchat.common.bridge.BridgeException;
import com.chatchat.common.bridge.BridgeRequest;
import com.chatchat.common.bridge.BridgeResponse;
import com.chatchat.common.bridge.BridgeStatus;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.mcpserver.mcp.McpToolApplicability;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.common.knowledge.template.TemplateResolutionEvent;
import com.chatchat.common.knowledge.template.TemplateResolutionException;
import com.chatchat.common.knowledge.template.TemplateServiceCall;
import com.chatchat.common.knowledge.template.TemplateServiceOperation;
import com.chatchat.common.knowledge.template.TemplateServicePort;
import com.chatchat.common.knowledge.template.TemplateServiceResult;
import com.chatchat.common.knowledge.template.TemplateServiceResultStatus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiToolSpecFactory extends AbstractRuntimeBridge<TemplateServiceCall, TemplateServiceResult>
    implements TemplateServicePort {

    public static final String BRIDGE_VERSION = "template_service_execution.v1";
    private static final BridgeContract CONTRACT = new BridgeContract(
        "template-service-execution", BRIDGE_VERSION, KernelProtocolCatalog.TEMPLATE_SERVICE,
        Set.of(TemplateServiceOperation.EXECUTE.operationCode()), KernelProtocolCatalog.SERVICE_BOUNDARY);

    private final ApiInvokeService invokeService;
    private final ApiServiceConfigService configService;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;
    private final StandardToolExecutionResultFactory standardResultFactory;

    public McpServerFeatures.SyncToolSpecification toGatewayToolSpecification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(ApiMcpToolPublisher.EXECUTE_TOOL_NAME)
            .title("API template execution gateway")
            .description("Execute one enabled API template selected from api_template_query. "
                + "templateId must be copied from templates[].templateId and all business arguments must be under parameters. "
                + "Raw URL, HTTP method, headers and body are forbidden.")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "templateId", Map.of("type", "string", "description", "Existing templateId returned by api_template_query"),
                "parameters", Map.of("type", "object", "additionalProperties", true,
                    "description", "Values declared by the selected template parameterSchema"),
                "purpose", Map.of("type", "string"),
                "sourceTaskId", Map.of("type", "string")
            ), List.of("templateId", "parameters"), false, null, null))
            .meta(apiTemplateGatewayMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                ApiMcpToolPublisher.EXECUTE_TOOL_NAME,
                "http",
                request.arguments(),
                () -> {
                    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                    TemplateServiceCall call = TemplateServiceCall.execute(text(arguments.get("templateId")),
                        readMap(arguments.get("parameters")), executionContext(arguments),
                        text(arguments.get("sourceTaskId")), deadline(arguments.get("deadlineAt")));
                    return gatewayCallResult(invoke(call, executionScope(arguments)));
                }))
            .build();
    }

    @Override
    public BridgeContract bridgeContract() {
        return CONTRACT;
    }

    @Override
    protected TemplateServiceResult exchangePayload(BridgeRequest<TemplateServiceCall> request) {
        TemplateServiceCall call = request.payload();
        if (call == null || call.operation() != TemplateServiceOperation.EXECUTE
            || !request.operation().equals(call.operation().operationCode())) {
            throw new BridgeException(BridgeStatus.REJECTED, "TEMPLATE_SERVICE_OPERATION_MISMATCH",
                "Template execution adapter requires a matching template service call");
        }
        if (call.expired(System.currentTimeMillis())) {
            throw new BridgeException(BridgeStatus.REJECTED, "TEMPLATE_SERVICE_DEADLINE_EXCEEDED",
                "Template service execution deadline has expired");
        }
        String templateId = call.templateId();
        if (templateId == null) {
            return resolutionResult(request.requestId(), call.operation(), TemplateResolutionEvent.missingId(
                request.requestId(), ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME));
        }
        ApiServiceConfig config = configService.findByToolName(templateId)
            .filter(ApiServiceConfig::isEnabled).orElse(null);
        if (config == null) {
            return resolutionResult(request.requestId(), call.operation(), TemplateResolutionEvent.notFound(
                request.requestId(), templateId, ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME));
        }
        Map<String, Object> arguments = new LinkedHashMap<>(call.context());
        arguments.put("templateId", templateId);
        arguments.put("parameters", call.parameters());
        log.info("MCP API template execution received templateId={} argKeys={}",
            templateId, argumentKeys(arguments));
        try {
            ApiInvokeResult invoked = invokeService.invoke(config, arguments);
            Map<String, Object> data = standardResultFactory.fromApi(config, invoked);
            return new TemplateServiceResult(TemplateServiceResult.SCHEMA_VERSION, request.requestId(), call.operation(),
                invoked.success() ? TemplateServiceResultStatus.SUCCESS : TemplateServiceResultStatus.FAILED,
                data, List.of(), false, Map.of("templateId", templateId), System.currentTimeMillis());
        } catch (TemplateResolutionException resolution) {
            return resolutionResult(request.requestId(), call.operation(),
                withRequestId(resolution.event(), request.requestId()));
        }
    }

    private Map<String, Object> apiTemplateGatewayMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "api_template_execute.v1");
        meta.put("communicationInputSchemaVersion", TemplateServicePayloadMapper.WIRE_CALL_SCHEMA_VERSION);
        meta.put("communicationOutputSchemaVersion", TemplateServicePayloadMapper.WIRE_RESULT_SCHEMA_VERSION);
        meta.put("kernelInputSchemaVersion", TemplateServiceCall.SCHEMA_VERSION);
        meta.put("kernelOutputSchemaVersion", TemplateServiceResult.SCHEMA_VERSION);
        meta.put("runtime_action", "execute");
        meta.put("runtimeAction", "execute");
        meta.put("templateGoverned", true);
        meta.put("templateDiscoveryTool", ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME);
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_EXECUTION, "mcp.api-template.v1", "templateId+parameters"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, ToolProtocolDriverContract.of(
            "mcp.api-template.v1",
            List.of(
                "Execute only a template accepted by the configured API template discovery/review flow; copy templates[].templateId into templateId and pass schema-declared values under parameters.",
                "Use capabilitySpec, outputSchema and dependencySpec for requirement coverage and ordering; candidate discovery alone is not execution evidence.",
                "Include only evidence-backed schema overrides. Omit unresolved optional/defaulted values so the authoritative template defaults apply."
            ),
            List.of(
                "Retain only evidence-backed schema overrides and omit optional/defaulted values so authoritative template defaults apply.",
                "A semantically rejected or incompatible template must not be retried unchanged; preserve rejection evidence and reselect from authorized candidates.",
                "Only a required parameter without a usable default may block that child; never invent raw URL, method, headers, or body fields."
            )
        ));
        return Map.copyOf(meta);
    }

    /**
     * Converts the value to tool specification.
     *
     * @param config the config value
     * @return the converted tool specification
     */
    public McpServerFeatures.SyncToolSpecification toToolSpecification(ApiServiceConfig config) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(config.getToolName())
            .title(config.getTitle())
            .description(config.getDescription() == null ? "External API service" : config.getDescription())
            .inputSchema(toInputSchema(config.getInputSchemaJson()))
            .meta(withLimitMeta(withProtocolMeta(withLegacyId(governanceFactory.metaForApi(config), "apiServiceId", config.getId()), config),
                config.getToolName(), "http"))
            .build();

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                config.getToolName(),
                "http",
                request.arguments(),
                () -> {
                log.info("MCP external API tool call received tool={} apiServiceId={} argKeys={}",
                    config.getToolName(),
                    config.getId(),
                    argumentKeys(request.arguments()));
                return toCallToolResult(config, invokeService.invoke(config, request.arguments()));
            }))
            .build();
    }

    /**
     * Converts the value to input schema.
     *
     * @param schemaJson the schema json value
     * @return the converted input schema
     */
    private McpSchema.JsonSchema toInputSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), true, null, null);
        }
        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, new TypeReference<>() {});
            String type = String.valueOf(schema.getOrDefault("type", "object"));
            Map<String, Object> properties = readMap(schema.get("properties"));
            List<String> required = readStringList(schema.get("required"));
            Boolean additionalProperties = schema.get("additionalProperties") instanceof Boolean value ? value : true;
            Map<String, Object> defs = readMap(schema.get("$defs"));
            Map<String, Object> definitions = readMap(schema.get("definitions"));
            return new McpSchema.JsonSchema(type, properties, required, additionalProperties, defs, definitions);
        } catch (Exception ex) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), true, null, null);
        }
    }

    /**
     * Converts the value to call tool result.
     *
     * @param result the result value
     * @return the converted call tool result
     */
    private McpSchema.CallToolResult toCallToolResult(ApiServiceConfig config, ApiInvokeResult result) {
        Map<String, Object> structured = standardResultFactory.fromApi(config, result);

        String text = result.success()
            ? summarizeBody(result.body(), result.rawBody())
            : result.errorMessage();

        return McpSchema.CallToolResult.builder()
            .addTextContent(text == null ? "" : text)
            .structuredContent(structured)
            .isError(!result.success())
            .build();
    }

    private McpSchema.CallToolResult gatewayCallResult(BridgeResponse<TemplateServiceResult> response) {
        if (!response.successful()) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("communicationSchemaVersion", TemplateServicePayloadMapper.WIRE_RESULT_SCHEMA_VERSION);
            failure.put("communicationRequestId", response.requestId());
            failure.put("communicationStatus", "FAILED");
            if (response.errorCode() != null) failure.put("errorCode", response.errorCode());
            if (response.errorMessage() != null) failure.put("errorMessage", response.errorMessage());
            return McpSchema.CallToolResult.builder()
                .addTextContent(response.errorMessage() == null ? "Template service bridge failed" : response.errorMessage())
                .structuredContent(Map.copyOf(failure)).isError(true).build();
        }
        TemplateServiceResult result = response.data();
        Map<String, Object> structured = TemplateServicePayloadMapper.payload(result);
        String summary = result.events().isEmpty()
            ? summarizeBody(structured, null) : result.events().get(0).message();
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary == null ? "" : summary).structuredContent(structured)
            .isError(!result.successful()).build();
    }

    private TemplateServiceResult resolutionResult(String requestId, TemplateServiceOperation operation,
                                          TemplateResolutionEvent event) {
        return new TemplateServiceResult(TemplateServiceResult.SCHEMA_VERSION, requestId, operation,
            TemplateServiceResultStatus.RESOLUTION_REQUIRED,
            Map.of("schemaVersion", "template_execution_resolution.v1", "success", false,
                "status", "RESOLUTION_REQUIRED", "event", event),
            List.of(event), false, Map.of(), System.currentTimeMillis());
    }

    private TemplateResolutionEvent withRequestId(TemplateResolutionEvent event, String requestId) {
        if (event.requestId() != null || requestId == null) return event;
        return new TemplateResolutionEvent(event.schemaVersion(), event.eventId(), requestId, event.type(),
            event.templateId(), event.missingParameters(), event.recoveryAction(), event.recoverable(),
            event.message(), event.context(), event.occurredAt());
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private KernelDataScope executionScope(Map<String, Object> arguments) {
        String requestId = firstText(text(arguments.get("requestId")), UUID.randomUUID().toString());
        return new KernelDataScope(firstText(text(arguments.get("tenantId")), "system"),
            text(arguments.get("userId")), requestId, text(arguments.get("conversationId")),
            text(arguments.get("runId")), firstText(text(arguments.get("environment")), text(arguments.get("env"))),
            Map.of("source", "mcp-api-execution-tool"));
    }

    private Map<String, Object> executionContext(Map<String, Object> arguments) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("purpose", "sourceTaskId", "requestId", "tenantId", "userId",
            "conversationId", "runId", "environment", "env")) {
            if (arguments.containsKey(key) && arguments.get(key) != null) context.put(key, arguments.get(key));
        }
        return context;
    }

    private long deadline(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try { return value == null ? 0 : Math.max(0, Long.parseLong(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Reads the map.
     *
     * @param value the value value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        return Map.of();
    }

    /**
     * Reads the string list.
     *
     * @param value the value value
     * @return the operation result
     */
    private List<String> readStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * Performs the summarize body operation.
     *
     * @param body the body value
     * @param rawBody the raw body value
     * @return the operation result
     */
    private String summarizeBody(Object body, String rawBody) {
        if (body == null) {
            return "";
        }
        if (body instanceof String text) {
            return text;
        }
        if (rawBody != null && !rawBody.isBlank()) {
            return rawBody;
        }
        try {
            return ModelProtocolJson.compact(body);
        } catch (Exception ex) {
            return String.valueOf(body);
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

    /**
     * Performs the with legacy id operation.
     *
     * @param meta the meta value
     * @param key the key value
     * @param value the value value
     * @return the operation result
     */
    private Map<String, Object> withLegacyId(Map<String, Object> meta, String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put(key, value);
        return values;
    }

    private Map<String, Object> withLimitMeta(Map<String, Object> meta, String toolName, String runtimeLevel) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put("mcp_tool_limit", concurrencyManager.limitMeta(toolName, runtimeLevel));
        return values;
    }

    private Map<String, Object> withProtocolMeta(Map<String, Object> meta, ApiServiceConfig config) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put("assetType", "api_service");
        values.put("targetRoutingRequired", false);
        values.put("templateId", config.getToolName());
        values.put("businessGroup", businessGroupMeta(config));
        values.put(McpToolApplicability.META_KEY, McpToolApplicability.of(
            "api_service:published_operation",
            "Published API operation: " + config.getToolName(),
            List.of("api_service"),
            "Invoke the published API operation " + config.getToolName() + " with its declared parameter contract.",
            List.of("The user-bound tool directly represents the required external API operation."),
            List.of("Discovering unrelated APIs", "Changing the bound endpoint", "Selecting or replacing Agent-bound tools")
        ));
        return values;
    }

    private Map<String, Object> businessGroupMeta(ApiServiceConfig config) {
        String code = firstText(config.getBusinessGroup(), "default");
        return Map.of(
            "code", code,
            "name", firstText(config.getBusinessGroupName(), code),
            "description", firstText(config.getBusinessGroupDescription(), "")
        );
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
