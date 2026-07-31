package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Applies default arguments and runtime-bound document filters before tool execution.
 */
@Slf4j
class AgentToolArgumentResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TemplateInvocationBridge TEMPLATE_INVOCATION_BRIDGE =
        new TemplateInvocationBridge();

    private final AgentToolNameResolver toolNames;
    private final int webSearchReferenceLimit;
    private final ToolRegistry toolRegistry;
    private final McpParamBindingResolver mcpParamBindingResolver = new McpParamBindingResolver();

    AgentToolArgumentResolver(AgentToolNameResolver toolNames, int webSearchReferenceLimit) {
        this(toolNames, webSearchReferenceLimit, null);
    }

    AgentToolArgumentResolver(AgentToolNameResolver toolNames, int webSearchReferenceLimit, ToolRegistry toolRegistry) {
        this.toolNames = toolNames;
        this.webSearchReferenceLimit = webSearchReferenceLimit;
        this.toolRegistry = toolRegistry;
    }

    Map<String, Object> applyDocumentSearchDefaults(String toolName,
                                                    Map<String, Object> arguments,
                                                    List<String> boundDocumentIds,
                                                    List<String> boundDocumentTags) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Collections.emptyMap() : arguments);
        if (!toolNames.isDocumentSearchToolName(toolName)) {
            return values;
        }
        if (!strictDocumentScope(values)) {
            values.remove("document_ids");
            values.remove("documentIds");
            values.remove("fileIds");
            values.remove("file_ids");
            values.remove("selectedDocumentIds");
            values.remove("selected_document_ids");
            values.remove("selectedFileIds");
            values.remove("selected_file_ids");
            values.remove("allowedDocIds");
            values.remove("allowed_doc_ids");
            values.remove("documentVisibilityEnforced");
            values.remove("document_visibility_enforced");
            values.remove("tags");
        } else if (!boundDocumentIds.isEmpty() && !hasAnyKey(values, "document_ids", "documentIds", "fileIds", "file_ids")) {
            values.put("document_ids", boundDocumentIds);
            if (!hasAnyKey(values, "selectedDocumentIds", "selected_document_ids", "selectedFileIds", "selected_file_ids", "allowedDocIds", "allowed_doc_ids")) {
                values.put("selectedDocumentIds", boundDocumentIds);
                values.put("documentVisibilityEnforced", true);
            }
            if (!boundDocumentTags.isEmpty() && !values.containsKey("tags")) {
                values.put("tags", boundDocumentTags);
            }
        }
        return values;
    }

    Map<String, Object> applyToolDefaults(String toolName,
                                          Map<String, Object> arguments,
                                          List<String> boundDocumentIds,
                                          List<String> boundDocumentTags,
                                          String query,
                                          int webSearchResultLimit) {
        Map<String, Object> values = applyDocumentSearchDefaults(toolName, arguments, boundDocumentIds, boundDocumentTags);
        if (toolNames.isDocumentSearchToolName(toolName) && !values.containsKey("query") && query != null && !query.isBlank()) {
            values.put("query", query);
        } else if (toolNames.isDocumentSearchToolName(toolName) && query != null && !query.isBlank()) {
            values.put("query", mergedDocumentQuery(query, Objects.toString(values.get("query"), "")));
        }
        if (isNotificationTool(toolName)) {
            return applyMcpParamBinding(toolName, applyNotificationDefaults(values, query), query);
        }
        if (!toolNames.isWebEvidenceToolName(toolName)) {
            return applyMcpParamBinding(toolName, values, query);
        }
        if (!values.containsKey("query") && query != null && !query.isBlank()) {
            values.put("query", query);
        }
        if (!values.containsKey("num_results")) {
            values.put("num_results", cappedLimit(webSearchResultLimit));
        }
        return applyMcpParamBinding(toolName, values, query);
    }

    Map<String, Object> defaultToolArguments(String toolName, String query, int webSearchResultLimit) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        if ("calculator".equals(toolName)) {
            return Map.of("expression", query);
        }
        if (toolNames.isWebSearchToolName(toolName)) {
            return Map.of("query", query, "num_results", cappedLimit(webSearchResultLimit));
        }
        if (toolNames.isSearchAndExtractToolName(toolName)) {
            return Map.of("query", query, "mode", "fast", "topK", cappedLimit(webSearchResultLimit));
        }
        if (isNotificationTool(toolName)) {
            return applyNotificationDefaults(Map.of(), query);
        }
        if (toolName != null && (toolName.startsWith("mcp_") || toolNames.isDocumentSearchToolName(toolName))) {
            return Map.of("query", query);
        }
        return Map.of("input", query);
    }

    /**
     * Compiles a template executor request from an observed MCP discovery contract. This closes the
     * legacy agent_chat path that otherwise lets a model select an executor without carrying the
     * discovered template id and logical target into the request.
     */
    Map<String, Object> applyObservedTemplateContract(String toolName,
                                                      Map<String, Object> arguments,
                                                      List<InteractionToolTrace> traces) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (traces == null || traces.isEmpty()) {
            return values;
        }
        String requestedTemplateId = scalarText(firstPresent(
            values, "template", "templateId", "template_id", "commandTemplate", "command_template"));
        for (int index = traces.size() - 1; index >= 0; index--) {
            InteractionToolTrace trace = traces.get(index);
            if (trace == null || !trace.isSuccess() || trace.getOutput() == null || trace.getOutput().isBlank()) {
                continue;
            }
            Object output = parseJson(trace.getOutput());
            if (output == null) {
                continue;
            }
            List<Map<String, Object>> candidates = discoveredTemplates(output).stream()
                .filter(template -> sameExecutor(toolName, discoveredExecutor(template)))
                .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            Map<String, Object> template = requestedTemplateId == null
                ? candidates.get(0)
                : candidates.stream()
                    .filter(candidate -> requestedTemplateId.equalsIgnoreCase(
                        scalarText(firstPresent(candidate, "templateId", "template_id", "id", "code"))))
                    .findFirst()
                    .orElse(candidates.get(0));
            String templateId = scalarText(firstPresent(template, "templateId", "template_id", "id", "code"));
            if (templateId == null) {
                continue;
            }
            if (apiTemplateExecutor(toolName)) {
                values.put("templateId", templateId);
            } else {
                values.put("template", templateId);
            }
            boolean valid = true;
            List<?> repairs = List.of();
            Map<String, Object> protocolTrace = Map.of();
            try {
                TemplateInvocationBridge.BridgeResult bridged = TEMPLATE_INVOCATION_BRIDGE.prepare(
                    new TemplateInvocationBridge.BridgeRequest(
                        toolName,
                        null,
                        templateId,
                        template,
                        values,
                        null,
                        false,
                        true
                    )
                );
                values = new LinkedHashMap<>(bridged.executorInput());
                repairs = bridged.repairs();
                protocolTrace = bridged.protocolTrace();
            } catch (TemplateInvocationBridge.TemplateBridgeException ex) {
                valid = false;
                // A denied bridge result must never carry unreviewed model parameters farther.
                values.put("parameters", Map.of());
                values.put(McpParamBindingResolver.STATUS_KEY, "DENIED");
                values.put(McpParamBindingResolver.CODE_KEY, "INVALID_TOOL_ARGUMENTS");
                values.put(McpParamBindingResolver.ERROR_KEY, ex.getMessage());
            }
            if (apiTemplateExecutor(toolName)) {
                values.remove("template");
            } else {
                values.remove("templateId");
            }
            Map<String, Object> context = mutableMap(values.get("executionContext"));
            Map<String, Object> selectedAsset = selectedAsset(output);
            putIfText(context, "assetName", firstPresent(selectedAsset, "name", "assetName", "asset_name"));
            putIfText(context, "env", firstPresent(selectedAsset, "environment", "env"));
            if (!context.isEmpty()) {
                values.put("executionContext", context);
            }
            log.info("Agent tool arguments compiled from observed template contract: tool={}, requestedTemplateId={}, "
                    + "templateId={}, sourceTool={}, parameterKeys={}, contextKeys={}, protocolTrace={}, repairs={}, valid={}",
                toolName,
                requestedTemplateId,
                templateId,
                trace.getToolName(),
                mutableMap(values.get("parameters")).keySet(),
                context.keySet(),
                protocolTrace,
                repairs,
                valid);
            return values;
        }
        return values;
    }

    /**
     * Applies a tool-published dependency-evidence adapter contract outside the
     * InterpretationPlan DAG path. Mandatory workflow recovery still has ordered
     * predecessor traces, so it must transport those successful structured outputs
     * instead of falling back to a query-only invocation.
     */
    Map<String, Object> applyPublishedDependencyEvidenceContract(
        String toolName,
        Map<String, Object> arguments,
        List<InteractionToolTrace> dependencyTraces
    ) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (toolRegistry == null || toolName == null || dependencyTraces == null || dependencyTraces.isEmpty()) {
            return values;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata == null || metadata.getMetadata() == null) {
            return values;
        }
        Map<String, Object> mcpMeta = mutableMap(metadata.getMetadata().get("mcpToolMeta"));
        Map<String, Object> contract = mutableMap(mcpMeta.get("inputAdapterContract"));
        if (!AgentProtocolCatalog.RUNTIME_DEPENDENCY_EVIDENCE.equals(
            scalarText(contract.get("contractVersion")))) {
            return values;
        }
        String parameter = scalarText(contract.get("dependencyEvidenceParameter"));
        if (parameter == null || values.containsKey(parameter)) {
            return values;
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (InteractionToolTrace trace : dependencyTraces) {
            if (trace == null || !trace.isSuccess() || trace.getOutput() == null || trace.getOutput().isBlank()) {
                continue;
            }
            Object output = parseJson(trace.getOutput());
            if (output == null) {
                continue;
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            if (trace.getToolName() != null && !trace.getToolName().isBlank()) {
                envelope.put("toolName", trace.getToolName());
            }
            envelope.put("output", output);
            evidence.add(Map.copyOf(envelope));
        }
        if (!evidence.isEmpty()) {
            values.put(parameter, List.copyOf(evidence));
            log.info("Agent fallback applied published dependency evidence contract: tool={}, parameter={}, evidenceCount={}",
                toolName, parameter, evidence.size());
        }
        return values;
    }

    private Object parseJson(String text) {
        try {
            return OBJECT_MAPPER.readValue(text, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> discoveredTemplates(Object value) {
        List<Map<String, Object>> templates = new ArrayList<>();
        collectDiscoveredTemplates(value, templates, 0);
        return templates;
    }

    @SuppressWarnings("unchecked")
    private void collectDiscoveredTemplates(Object value, List<Map<String, Object>> templates, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectDiscoveredTemplates(item, templates, depth + 1));
            return;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        Object templateId = firstPresent(map, "templateId", "template_id", "id", "code");
        if (scalarText(templateId) != null && discoveredExecutor(map) != null) {
            templates.add(map);
            return;
        }
        for (String key : List.of("templates", "associatedTemplates", "associated_templates", "results", "items",
            "data", "result", "payload", "structuredContent", "structured_content")) {
            collectDiscoveredTemplates(map.get(key), templates, depth + 1);
        }
    }

    private String discoveredExecutor(Map<String, Object> template) {
        return firstNonBlank(
            scalarText(nested(template, "parameterContract", "executionTool")),
            scalarText(nested(template, "parameter_contract", "execution_tool")),
            scalarText(nested(template, "invocationExample", "tool")),
            scalarText(nested(template, "execution", "executorTool")),
            scalarText(nested(template, "sqlExecutionBinding", "toolName"))
        );
    }

    private boolean sameExecutor(String actualTool, String declaredExecutor) {
        if (actualTool == null || declaredExecutor == null) {
            return false;
        }
        String actual = actualTool.trim().toLowerCase(Locale.ROOT);
        String declared = declaredExecutor.trim().toLowerCase(Locale.ROOT);
        return actual.equals(declared) || actual.endsWith("_" + declared);
    }

    private boolean apiTemplateExecutor(String toolName) {
        if (toolName == null) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return "api_template_execute".equals(normalized) || normalized.endsWith("_api_template_execute");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> selectedAsset(Object output) {
        if (!(output instanceof Map<?, ?> root)) {
            return Map.of();
        }
        Object selected = nested((Map<String, Object>) root, "queryIr", "asset", "selected");
        return selected instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> source, String... path) {
        Object current = source;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
    }

    private void putIfText(Map<String, Object> target, String key, Object value) {
        String text = scalarText(value);
        if (text != null) {
            target.put(key, text);
        }
    }

    private String scalarText(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || text.contains("<logical ") || text.contains("<env>") ? null : text;
    }

    private Map<String, Object> applyNotificationDefaults(Map<String, Object> arguments, String query) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        String content = firstNonBlank(
            stringValue(values.get("content")),
            stringValue(values.get("message")),
            stringValue(values.get("text")),
            stringValue(values.get("query")),
            query
        );
        if (!hasText(values.get("title"))) {
            values.put("title", "Agent 告警通知");
        }
        if (!hasText(values.get("content")) && content != null) {
            values.put("content", content);
        }
        if (!hasText(values.get("level"))) {
            values.put("level", inferNotificationLevel(content));
        }
        values.remove("query");
        return values;
    }

    private Map<String, Object> applyMcpParamBinding(String toolName, Map<String, Object> arguments, String query) {
        return mcpParamBindingResolver.resolve(
            toolName,
            toolRegistry == null ? null : toolRegistry.getToolMetadata(toolName),
            arguments,
            query
        );
    }

    private boolean isNotificationTool(String toolName) {
        if (toolName == null || toolName.isBlank() || toolRegistry == null) {
            return false;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata == null) {
            return false;
        }
        if ("notification".equalsIgnoreCase(firstNonBlank(metadata.getCategory(), ""))) {
            return true;
        }
        if ("notify".equalsIgnoreCase(firstNonBlank(metadata.getOperationType(), ""))) {
            return true;
        }
        if (metadata.getCategories() != null && metadata.getCategories().stream()
            .filter(Objects::nonNull)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains("notification"))) {
            return true;
        }
        Object marker = metadata.getMetadata() == null ? null : metadata.getMetadata().get("notificationTool");
        return marker instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(marker));
    }

    private String inferNotificationLevel(String content) {
        String text = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (text.contains("critical") || text.contains("严重") || text.contains("紧急")) {
            return "CRITICAL";
        }
        if (text.contains("warning") || text.contains("告警") || text.contains("异常")
            || text.contains("失败") || text.contains("风险")) {
            return "WARNING";
        }
        return "INFO";
    }

    private int cappedLimit(int webSearchResultLimit) {
        return Math.max(1, Math.min(webSearchReferenceLimit, webSearchResultLimit));
    }

    private boolean strictDocumentScope(Map<String, Object> values) {
        Object strict = firstPresent(values, "strict_document_scope", "strictDocumentScope");
        if (strict instanceof Boolean flag) {
            return flag;
        }
        Object scopeMode = firstPresent(values, "scope_mode", "scopeMode");
        return scopeMode != null && "strict".equalsIgnoreCase(String.valueOf(scopeMode).trim());
    }

    private boolean hasAnyKey(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private String firstNonBlank(String... values) {
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

    private String mergedDocumentQuery(String originalQuery, String plannedQuery) {
        String original = originalQuery == null ? "" : originalQuery.trim();
        String planned = plannedQuery == null ? "" : plannedQuery.trim();
        if (original.isBlank()) {
            return planned;
        }
        if (planned.isBlank()) {
            return original;
        }
        if (compact(planned).contains(compact(original))) {
            return planned;
        }
        if (compact(original).contains(compact(planned))) {
            return original;
        }
        return original + " " + planned;
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }
}
