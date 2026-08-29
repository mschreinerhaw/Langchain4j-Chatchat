package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.orchestration.retrieval.McpParamBindingResolver;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge;
import com.chatchat.agents.runtime.toolcall.TemplateExecutionContractSelector;
import com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler;
import com.chatchat.common.knowledge.template.TemplateResolutionEvent;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.mcp.contract.McpTemplateBindingEvidence;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies default arguments and runtime-bound document filters before tool execution.
 */
@Slf4j
public class AgentToolArgumentResolver {

    public static final String RUNTIME_OWNED_TEMPLATE_BATCH_MARKER = "__runtimeOwnedTemplateBatch";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TemplateInvocationBridge TEMPLATE_INVOCATION_BRIDGE =
        new TemplateInvocationBridge();
    private static final TemplateExecutionContractSelector TEMPLATE_CONTRACT_SELECTOR =
        new TemplateExecutionContractSelector();

    private final AgentToolNameResolver toolNames;
    private final int webSearchReferenceLimit;
    private final ToolRegistry toolRegistry;
    private final McpParamBindingResolver mcpParamBindingResolver = new McpParamBindingResolver();

    public AgentToolArgumentResolver(AgentToolNameResolver toolNames, int webSearchReferenceLimit) {
        this(toolNames, webSearchReferenceLimit, null);
    }

    public AgentToolArgumentResolver(AgentToolNameResolver toolNames, int webSearchReferenceLimit, ToolRegistry toolRegistry) {
        this.toolNames = toolNames;
        this.webSearchReferenceLimit = webSearchReferenceLimit;
        this.toolRegistry = toolRegistry;
    }

    public Map<String, Object> applyDocumentSearchDefaults(String toolName,
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

    public Map<String, Object> applyToolDefaults(String toolName,
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

    public Map<String, Object> defaultToolArguments(String toolName, String query, int webSearchResultLimit) {
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
    public Map<String, Object> applyObservedTemplateContract(String toolName,
                                                      Map<String, Object> arguments,
                                                      List<InteractionToolTrace> traces) {
        return applyObservedTemplateContract(toolName, arguments, traces,
            scalarText(firstPresent(arguments, "purpose", "reason", "query")));
    }

    public Map<String, Object> applyObservedTemplateContract(String toolName,
                                                      Map<String, Object> arguments,
                                                      List<InteractionToolTrace> traces,
                                                      String userQuery) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        // This marker is Runtime-owned. Never accept a planner/model supplied value; a
        // successfully recompiled observed batch below will add it again.
        values.remove(RUNTIME_OWNED_TEMPLATE_BATCH_MARKER);
        if (traces == null || traces.isEmpty()) {
            return values;
        }
        String requestedTemplateId = runtimeScalarText(firstPresent(
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
            if (candidates.size() > 1) {
                Set<String> reviewedIds = reviewedTemplateIds(output);
                if (reviewedIds.isEmpty()) {
                    return templateRequirementReviewDenied(values, candidates.size());
                }
                candidates = candidates.stream()
                    .filter(template -> {
                        String id = templateId(template);
                        return id != null && reviewedIds.contains(id.toLowerCase(Locale.ROOT));
                    })
                    .toList();
                if (candidates.isEmpty()) {
                    return templateRequirementReviewDenied(values, 0);
                }
                if (requestedTemplateId != null && candidates.stream()
                    .map(this::templateId)
                    .filter(Objects::nonNull)
                    .noneMatch(id -> id.equalsIgnoreCase(requestedTemplateId))) {
                    return templateRequirementReviewDenied(values, candidates.size());
                }
            }
            if (batchCalls(values) != null) {
                Map<String, Object> reviewedBatch = reviewedInvocationBatch(
                    toolName, values, candidates, output);
                if (reviewedBatch != null) {
                    values = reviewedBatch;
                }
                return applyObservedBatchTemplateContracts(toolName, values, candidates, output, trace, userQuery);
            }
            TemplateExecutionContractSelector.Selection contractSelection = TEMPLATE_CONTRACT_SELECTOR.select(
                candidates, requestedTemplateId, toolName, null);
            if (!contractSelection.selected()) {
                return templateContractDenied(values, contractSelection);
            }
            List<Map<String, Object>> eligible = List.of(contractSelection.template());
            TemplateInvocationBridge.TemplateBridgeException lastFailure = null;
            Map<String, Object> failedInput = null;
            Map<String, Object> failedTemplate = null;
            String failedTemplateId = null;
            for (Map<String, Object> template : eligible) {
                String templateId = templateId(template);
                if (templateId == null) {
                    continue;
                }
                Map<String, Object> candidateInput = new LinkedHashMap<>(values);
                if (usesTemplateIdField(toolName)) {
                    candidateInput.put("templateId", templateId);
                } else {
                    candidateInput.put("template", templateId);
                }
                try {
                    TemplateInvocationBridge.BridgeResult bridged = TEMPLATE_INVOCATION_BRIDGE.prepare(
                        new TemplateInvocationBridge.BridgeRequest(
                            toolName,
                            null,
                            templateId,
                            template,
                            candidateInput,
                            parameterProtocol(candidateInput),
                            false,
                            true,
                            new TemplateInvocationBridge.EvidenceContext(userQuery, Map.of())
                        )
                    );
                    values = new LinkedHashMap<>(bridged.executorInput());
                    boolean templateBoundContext = mergeObservedExecutionContext(values, template);
                    finishObservedTemplateInput(toolName, values, output, templateBoundContext);
                    putRuntimeTemplateBinding(values, templateId, toolName,
                        "reviewed_template_discovery_agent");
                    logObservedTemplateContract(toolName, requestedTemplateId, templateId, trace,
                        values, bridged.protocolTrace(), bridged.repairs(), true);
                    return values;
                } catch (TemplateInvocationBridge.TemplateBridgeException ex) {
                    lastFailure = ex;
                    failedInput = candidateInput;
                    failedTemplate = template;
                    failedTemplateId = templateId;
                    // With no explicit template selection, keep walking the ranked discovery
                    // result. A parameterized first candidate must not mask a later executable
                    // candidate. Explicit selections remain authoritative and fail closed.
                    if (requestedTemplateId != null) {
                        break;
                    }
                }
            }
            if (lastFailure == null) {
                continue;
            }
            if (failedInput != null) {
                values = new LinkedHashMap<>(failedInput);
            }
            values.put("parameters", Map.of());
            values.put(McpParamBindingResolver.STATUS_KEY, "DENIED");
            values.put(McpParamBindingResolver.CODE_KEY, "INVALID_TOOL_ARGUMENTS");
            values.put(McpParamBindingResolver.ERROR_KEY, lastFailure.getMessage());
            values.put("templateResolutionEvent", templateResolutionEvent(
                lastFailure, failedTemplateId, failedTemplate, trace.getToolName()));
            logObservedTemplateContract(toolName, requestedTemplateId, failedTemplateId, trace,
                values, Map.of(), List.of(), false);
            return values;
        }
        return values;
    }

    private Map<String, Object> templateRequirementReviewDenied(
        Map<String, Object> input,
        int admittedCount
    ) {
        Map<String, Object> denied = new LinkedHashMap<>(input == null ? Map.of() : input);
        denied.put(McpParamBindingResolver.STATUS_KEY, "DENIED");
        denied.put(McpParamBindingResolver.CODE_KEY, "TEMPLATE_REQUIREMENT_REVIEW_REQUIRED");
        denied.put(McpParamBindingResolver.ERROR_KEY,
            "Multiple business-template candidates require semantic admission from the original user question "
                + "and cumulative analysis context before execution; admittedCount=" + admittedCount);
        return denied;
    }

    private Map<String, Object> templateContractDenied(
        Map<String, Object> input,
        TemplateExecutionContractSelector.Selection selection
    ) {
        Map<String, Object> denied = new LinkedHashMap<>(input == null ? Map.of() : input);
        denied.put(McpParamBindingResolver.STATUS_KEY, "DENIED");
        denied.put(McpParamBindingResolver.CODE_KEY, selection.code());
        denied.put(McpParamBindingResolver.ERROR_KEY, selection.reason());
        denied.put("templateContractRejections", selection.rejections());
        denied.put("executableTemplateCandidateCount", selection.executableCandidateCount());
        return denied;
    }

    /**
     * Keeps a unique typed asset discovery result authoritative across the legacy
     * agent loop. Model review may improve template ranking, but it must never move
     * a continuation tool to a different logical asset.
     */
    public Map<String, Object> enforceObservedAssetContinuity(String toolName,
                                                       Map<String, Object> arguments,
                                                       List<InteractionToolTrace> traces) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (!assetScopedContinuationTool(toolName, values)
            || "DENIED".equals(values.get(McpParamBindingResolver.STATUS_KEY))) {
            return values;
        }
        Map<String, Object> canonical = uniqueObservedAsset(traces);
        if (canonical.isEmpty()) {
            return values;
        }
        boolean templateDiscovery = templateDiscoveryTool(toolName);
        String envelopeKey = templateDiscovery ? "filters" : "executionContext";
        Map<String, Object> target = mutableMap(firstPresent(values, envelopeKey,
            templateDiscovery ? "executionContext" : "mcpExecutionContext"));
        String mismatch = assetMismatch(canonical, target, traces);
        if (mismatch != null) {
            values.put(McpParamBindingResolver.STATUS_KEY, "DENIED");
            values.put(McpParamBindingResolver.CODE_KEY, "ASSET_CONTEXT_MISMATCH");
            values.put(McpParamBindingResolver.ERROR_KEY, mismatch);
            log.warn("Agent continuation rejected because observed asset context drifted: tool={}, error={}",
                toolName, mismatch);
            return values;
        }
        putIfText(target, "assetName", firstPresent(canonical, "name", "assetName", "asset_name"));
        putIfText(target, "env", firstPresent(canonical, "environment", "env"));
        if (!templateDiscovery) {
            putIfText(target, "assetId", firstPresent(canonical, "id", "assetId", "asset_id"));
            putIfText(target, "assetToolName", firstPresent(canonical, "toolName", "tool_name"));
        }
        values.put(envelopeKey, target);
        return values;
    }

    private Map<String, Object> uniqueObservedAsset(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> unique = Map.of();
        for (InteractionToolTrace trace : traces) {
            if (trace == null || !trace.isSuccess()
                || trace.getOutput() == null || trace.getOutput().isBlank()) {
                continue;
            }
            List<Map<String, Object>> assets = discoveredAssets(parseJson(trace.getOutput()));
            if (assets.size() != 1) {
                continue;
            }
            Map<String, Object> candidate = assets.get(0);
            if (!unique.isEmpty() && !sameObservedAsset(unique, candidate)) {
                return Map.of();
            }
            unique = candidate;
        }
        return unique;
    }

    private List<Map<String, Object>> discoveredAssets(Object value) {
        List<Map<String, Object>> assets = new ArrayList<>();
        collectDiscoveredAssets(value, assets, 0);
        List<Map<String, Object>> unique = new ArrayList<>();
        for (Map<String, Object> candidate : assets) {
            if (unique.stream().noneMatch(existing -> sameObservedAsset(existing, candidate))) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    @SuppressWarnings("unchecked")
    private void collectDiscoveredAssets(Object value, List<Map<String, Object>> assets, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectDiscoveredAssets(item, assets, depth + 1));
            return;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        Object asset = map.get("asset");
        if (asset instanceof Map<?, ?> assetMap
            && scalarText(firstPresent((Map<String, Object>) assetMap, "id", "assetId", "name")) != null) {
            assets.add(new LinkedHashMap<>((Map<String, Object>) assetMap));
            return;
        }
        for (String key : List.of("assets", "data", "result", "payload", "structuredContent",
            "structured_content", "routingProjection")) {
            collectDiscoveredAssets(map.get(key), assets, depth + 1);
        }
    }

    private boolean sameObservedAsset(Map<String, Object> left, Map<String, Object> right) {
        String leftId = scalarText(firstPresent(left, "id", "assetId", "asset_id"));
        String rightId = scalarText(firstPresent(right, "id", "assetId", "asset_id"));
        if (leftId != null && rightId != null) {
            return leftId.equals(rightId);
        }
        return Objects.equals(
            scalarText(firstPresent(left, "name", "assetName", "asset_name")),
            scalarText(firstPresent(right, "name", "assetName", "asset_name"))
        );
    }

    private String assetMismatch(Map<String, Object> canonical,
                                 Map<String, Object> supplied,
                                 List<InteractionToolTrace> traces) {
        String canonicalId = scalarText(firstPresent(canonical, "id", "assetId", "asset_id"));
        String suppliedId = scalarText(firstPresent(supplied, "assetId", "asset_id"));
        if (canonicalId != null && suppliedId != null
            && !canonicalId.equals(suppliedId)
            && !canonicalAssetReference(suppliedId, "id", "assetId")) {
            String canonicalName = scalarText(firstPresent(canonical, "name", "assetName", "asset_name"));
            String suppliedName = scalarText(firstPresent(supplied, "assetName", "asset_name", "name"));
            return "Asset continuation supplied assetId=" + suppliedId + " (" + suppliedName + ")"
                + " but prior unique discovery established assetId=" + canonicalId + " (" + canonicalName + ")";
        }
        String canonicalName = scalarText(firstPresent(canonical, "name", "assetName", "asset_name"));
        String suppliedName = scalarText(firstPresent(supplied, "assetName", "asset_name", "name"));
        if (canonicalName != null && suppliedName != null
            && !sameAssetIdentityText(canonicalName, suppliedName)
            && !canonicalAssetReference(suppliedName, "name")
            && !verifiedObservedAssetAlias(canonical, suppliedName, traces)) {
            return "Asset continuation supplied assetName=" + suppliedName
                + " but prior unique discovery established assetName=" + canonicalName;
        }
        String canonicalEnv = scalarText(firstPresent(canonical, "environment", "env"));
        String suppliedEnv = scalarText(firstPresent(supplied, "environment", "env"));
        if (canonicalEnv != null && suppliedEnv != null
            && !canonicalEnv.equalsIgnoreCase(suppliedEnv)
            && !canonicalAssetReference(suppliedEnv, "environment", "env")) {
            return "Asset continuation supplied env=" + suppliedEnv
                + " but prior unique discovery established env=" + canonicalEnv;
        }
        return null;
    }

    /**
     * A fallback agent continuation can carry the canonical discovery path before
     * the plan binding layer has materialized it. Treat only published asset-view
     * paths as deferred references; arbitrary JSONPath remains a context mismatch.
     * The caller subsequently replaces the reference with the reviewed asset value.
     */
    private boolean canonicalAssetReference(String value, String... fields) {
        if (value == null || fields == null) {
            return false;
        }
        String normalized = value.trim();
        for (String field : fields) {
            if (("$.assets[0].asset." + field).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean verifiedObservedAssetAlias(Map<String, Object> canonical,
                                               String suppliedName,
                                               List<InteractionToolTrace> traces) {
        if (suppliedName == null || traces == null) {
            return false;
        }
        for (Object identity : new Object[] {
            firstPresent(canonical, "name", "assetName", "asset_name"),
            firstPresent(canonical, "displayName", "display_name"),
            firstPresent(canonical, "toolName", "tool_name"),
            firstPresent(canonical, "id", "assetId", "asset_id")
        }) {
            if (identity != null && sameAssetIdentityText(String.valueOf(identity), suppliedName)) {
                return true;
            }
        }
        for (InteractionToolTrace trace : traces) {
            if (trace == null || !trace.isSuccess() || !assetDiscoveryTool(trace.getToolName())) {
                continue;
            }
            Object output = parseJson(trace.getOutput());
            List<Map<String, Object>> assets = discoveredAssets(output);
            if (assets.size() != 1 || !sameObservedAsset(canonical, assets.get(0))
                || !(output instanceof Map<?, ?> rawOutput)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outputMap = (Map<String, Object>) rawOutput;
            Object rawFilters = firstPresent(outputMap, "filters", "queryFilters", "query_filters");
            if (!(rawFilters instanceof Map<?, ?> filters)) {
                continue;
            }
            String queryName = scalarText(firstPresent(filters, "assetName", "asset_name", "name"));
            if (sameAssetIdentityText(queryName, suppliedName)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameAssetIdentityText(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean assetScopedContinuationTool(String toolName, Map<String, Object> arguments) {
        if (assetDiscoveryTool(toolName)) {
            return false;
        }
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        return templateDiscoveryTool(toolName)
            || normalized.contains("command_execute")
            || normalized.contains("query_execute")
            || normalized.contains("script_execute")
            || normalized.contains("template_execute")
            || arguments.containsKey("executionContext")
            || arguments.containsKey("mcpExecutionContext");
    }

    private boolean assetDiscoveryTool(String toolName) {
        return workflowRole(toolName) == ToolWorkflowRole.ASSET_DISCOVERY;
    }

    private boolean templateDiscoveryTool(String toolName) {
        return workflowRole(toolName) == ToolWorkflowRole.TEMPLATE_DISCOVERY;
    }

    private ToolWorkflowRole workflowRole(String toolName) {
        if (toolRegistry != null) {
            ToolWorkflowRole role = toolRegistry.getWorkflowRole(toolName);
            if (role != null) return role;
            return ToolWorkflowContract.resolveRole(toolName, toolRegistry.getToolMetadata(toolName));
        }
        return ToolWorkflowContract.resolveRole(toolName, null);
    }

    private List<Map<String, Object>> eligibleTemplates(List<Map<String, Object>> candidates,
                                                         String requestedTemplateId) {
        if (requestedTemplateId == null) {
            return candidates;
        }
        List<Map<String, Object>> exact = candidates.stream()
            .filter(candidate -> requestedTemplateId.equalsIgnoreCase(templateId(candidate)))
            .toList();
        // Replacing an invented reference is safe only when discovery produced one unambiguous
        // Runtime-owned candidate. Never turn a missing id into "pick the first" of many.
        return exact.isEmpty() && candidates.size() == 1 ? candidates : exact;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyObservedBatchTemplateContracts(String toolName,
                                                                     Map<String, Object> values,
                                                                     List<Map<String, Object>> candidates,
                                                                     Object output,
                                                                     InteractionToolTrace trace,
                                                                     String userQuery) {
        List<?> rawCalls = batchCalls(values);
        List<Map<String, Object>> compiledCalls = new ArrayList<>();
        for (int index = 0; index < rawCalls.size(); index++) {
            Object rawCall = rawCalls.get(index);
            if (!(rawCall instanceof Map<?, ?> callMap)) {
                return deniedBatch(values, "Batch call " + index + " must be an object");
            }
            Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) callMap);
            Map<String, Object> childInput = mutableMap(firstPresent(call, "arguments", "input"));
            String childTool = firstNonBlank(
                scalarText(firstPresent(call, "toolName", "tool_name")), toolName);
            String childTemplateId = scalarText(firstPresent(
                childInput, "template", "templateId", "template_id", "commandTemplate", "command_template"));
            if (childTemplateId == null) {
                childTemplateId = scalarText(firstPresent(
                    call, "template", "templateId", "template_id", "commandTemplate", "command_template"));
            }
            List<Map<String, Object>> compatibleCandidates = candidates.stream()
                .filter(candidate -> sameExecutor(childTool, discoveredExecutor(candidate)))
                .toList();
            TemplateExecutionContractSelector.Selection childSelection = TEMPLATE_CONTRACT_SELECTOR.select(
                compatibleCandidates, childTemplateId, childTool, null);
            if (!childSelection.selected()) {
                return deniedBatch(values, "Batch call " + index + " template " + childTemplateId
                    + " was not returned as one unambiguous executable discovery contract ["
                    + childSelection.code() + "]: " + childSelection.reason());
            }
            Map<String, Object> template = childSelection.template();
            childTemplateId = childSelection.templateId();
            if (usesTemplateIdField(childTool)) {
                childInput.put("templateId", childTemplateId);
                childInput.remove("template");
            } else {
                childInput.put("template", childTemplateId);
                childInput.remove("templateId");
            }
            try {
                TemplateInvocationBridge.BridgeResult bridged = TEMPLATE_INVOCATION_BRIDGE.prepare(
                    new TemplateInvocationBridge.BridgeRequest(
                        childTool, null, childTemplateId, template, childInput,
                        parameterProtocol(childInput), false, true,
                        new TemplateInvocationBridge.EvidenceContext(userQuery, Map.of())
                    )
                );
                Map<String, Object> compiledInput = new LinkedHashMap<>(bridged.executorInput());
                boolean templateBoundContext = mergeObservedExecutionContext(compiledInput, template);
                finishObservedTemplateInput(childTool, compiledInput, output, templateBoundContext);
                putRuntimeTemplateBinding(compiledInput, childTemplateId, childTool,
                    "reviewed_template_discovery_agent_batch");
                call.put("arguments", compiledInput);
                call.remove("input");
                call.remove("template");
                call.remove("templateId");
                call.remove("template_id");
                compiledCalls.add(call);
            } catch (TemplateInvocationBridge.TemplateBridgeException ex) {
                return deniedBatch(values, "Batch call " + index + " rejected: " + ex.getMessage());
            }
        }
        values.put("calls", compiledCalls);
        values.remove("toolCalls");
        values.remove("tool_calls");
        values.remove("executionContext");
        values.remove("mcpExecutionContext");
        values.put(RUNTIME_OWNED_TEMPLATE_BATCH_MARKER, true);
        log.info("Agent batch arguments compiled from observed template contracts: tool={}, sourceTool={}, callCount={}",
            toolName, trace.getToolName(), compiledCalls.size());
        return values;
    }

    private Map<String, Object> deniedBatch(Map<String, Object> values, String error) {
        Map<String, Object> denied = new LinkedHashMap<>(values);
        denied.put(McpParamBindingResolver.STATUS_KEY, "DENIED");
        denied.put(McpParamBindingResolver.CODE_KEY, "INVALID_TOOL_ARGUMENTS");
        denied.put(McpParamBindingResolver.ERROR_KEY, error);
        return denied;
    }

    /**
     * Replaces pre-discovery planner placeholders with the complete evidence-reviewed
     * invocation set.  The returned calls are still compiled by
     * {@link #applyObservedBatchTemplateContracts}; this method performs no trust
     * elevation and refuses partial, duplicate or executor-incompatible mappings.
     */
    private Map<String, Object> reviewedInvocationBatch(String toolName,
                                                        Map<String, Object> values,
                                                        List<Map<String, Object>> candidates,
                                                        Object output) {
        List<Map<String, Object>> invocations = reviewedTemplateInvocations(output);
        if (invocations.isEmpty() || candidates == null || candidates.size() < 2) {
            return null;
        }
        Map<String, Map<String, Object>> admitted = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            String id = templateId(candidate);
            if (id != null) admitted.putIfAbsent(id.toLowerCase(Locale.ROOT), candidate);
        }
        Map<String, Map<String, Object>> callsByTemplate = new LinkedHashMap<>();
        for (Map<String, Object> invocation : invocations) {
            String id = scalarText(firstPresent(invocation,
                "templateId", "template_id", "template", "commandTemplate", "command_template"));
            String actionTool = scalarText(firstPresent(invocation, "toolName", "tool_name", "tool"));
            if (id == null || !admitted.containsKey(id.toLowerCase(Locale.ROOT))
                || (actionTool != null && !sameExecutor(toolName, actionTool))) {
                return null;
            }
            Map<String, Object> arguments = mutableMap(firstPresent(
                invocation, "arguments", "inputChanges", "input_changes", "input"));
            if (arguments.isEmpty()) return null;
            arguments.put("templateId", id);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("toolName", toolName);
            call.put("arguments", arguments);
            String intent = scalarText(firstPresent(invocation, "intent", "purpose"));
            if (intent != null) call.put("purpose", intent);
            if (callsByTemplate.putIfAbsent(id.toLowerCase(Locale.ROOT), call) != null) {
                return null;
            }
        }
        if (!callsByTemplate.keySet().equals(admitted.keySet())) {
            return null;
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        int index = 1;
        for (String id : admitted.keySet()) {
            Map<String, Object> call = new LinkedHashMap<>(callsByTemplate.get(id));
            call.put("callId", "reviewed-template-" + index++);
            calls.add(call);
        }
        Map<String, Object> batch = new LinkedHashMap<>();
        copyIfPresent(values, batch, "executionMode", "batchId", "stopOnFailure");
        batch.put("calls", List.copyOf(calls));
        log.info("Agent batch placeholders replaced by evidence-reviewed invocations: tool={}, callCount={}",
            toolName, calls.size());
        return batch;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> reviewedTemplateInvocations(Object value) {
        List<Map<String, Object>> invocations = new ArrayList<>();
        collectReviewedTemplateInvocations(value, invocations, 0);
        return List.copyOf(invocations);
    }

    @SuppressWarnings("unchecked")
    private void collectReviewedTemplateInvocations(Object value,
                                                    List<Map<String, Object>> invocations,
                                                    int depth) {
        if (value == null || depth > 8) return;
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectReviewedTemplateInvocations(item, invocations, depth + 1));
            return;
        }
        if (!(value instanceof Map<?, ?> raw)) return;
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        Object selection = map.get("runtimeTemplateSelection");
        if (selection instanceof Map<?, ?> rawSelection) {
            Object reviewed = mutableMap(rawSelection).get("reviewedInvocations");
            if (reviewed instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    if (item instanceof Map<?, ?> invocation) {
                        invocations.add(new LinkedHashMap<>((Map<String, Object>) invocation));
                    }
                }
            }
        }
        for (Object nested : map.values()) {
            collectReviewedTemplateInvocations(nested, invocations, depth + 1);
        }
    }

    private void copyIfPresent(Map<String, Object> source,
                               Map<String, Object> target,
                               String... keys) {
        for (String key : keys) {
            if (source.get(key) != null) target.put(key, source.get(key));
        }
    }

    private List<?> batchCalls(Map<String, Object> values) {
        Object calls = firstPresent(values, "calls", "toolCalls", "tool_calls");
        return calls instanceof List<?> list && !list.isEmpty() ? list : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameterProtocol(Map<String, Object> input) {
        Object protocol = firstPresent(input, "parameterProtocol", "parameter_protocol");
        return protocol instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : null;
    }

    private String templateId(Map<String, Object> template) {
        return scalarText(firstPresent(template, "templateId", "template_id", "id", "code"));
    }

    private void finishObservedTemplateInput(String toolName,
                                             Map<String, Object> values,
                                             Object output,
                                             boolean templateBoundContext) {
        if (usesTemplateIdField(toolName)) {
            values.remove("template");
        } else {
            values.remove("templateId");
        }
        Map<String, Object> context = mutableMap(values.get("executionContext"));
        Map<String, Object> selectedAsset = selectedAsset(output);
        if (templateBoundContext) {
            // A template-owned binding is already the complete routing identity for
            // this child. Only a neutral environment default may be inherited.
            putIfTextAbsent(context, "env", firstPresent(selectedAsset, "environment", "env"));
        } else {
            // Without a template-owned binding, the reviewed unique discovery asset
            // remains authoritative over planner-authored routing text.
            putIfText(context, "assetName", firstPresent(selectedAsset, "name", "assetName", "asset_name"));
            putIfText(context, "env", firstPresent(selectedAsset, "environment", "env"));
            putIfText(context, "assetId", firstPresent(selectedAsset, "id", "assetId", "asset_id"));
            putIfText(context, "assetToolName", firstPresent(selectedAsset, "toolName", "tool_name"));
        }
        if (!context.isEmpty()) {
            values.put("executionContext", context);
        }
    }

    /**
     * Carries runtime-owned routing context from the discovered template into the
     * executor request. Template discovery contracts use a few compatible binding
     * envelopes, so resolve them structurally instead of coupling this bridge to a
     * particular datasource, environment, or template id.
     */
    private boolean mergeObservedExecutionContext(Map<String, Object> values, Map<String, Object> template) {
        Object observedValue = null;
        for (Object candidate : new Object[] {
            nested(template, "sqlExecutionBinding", "executionContext"),
            nested(template, "executionBinding", "executionContext"),
            nested(template, "execution", "executionContext"),
            template.get("executionContext")
        }) {
            if (candidate != null) {
                observedValue = candidate;
                break;
            }
        }
        Map<String, Object> observed = mutableMap(observedValue);
        if (observed.isEmpty()) {
            return false;
        }
        Map<String, Object> context = mutableMap(values.get("executionContext"));
        context.putAll(observed);
        values.put("executionContext", context);
        return true;
    }

    private void logObservedTemplateContract(String toolName,
                                             String requestedTemplateId,
                                             String templateId,
                                             InteractionToolTrace trace,
                                             Map<String, Object> values,
                                             Map<String, Object> protocolTrace,
                                             List<?> repairs,
                                             boolean valid) {
        log.info("Agent tool arguments compiled from observed template contract: tool={}, requestedTemplateId={}, "
                + "templateId={}, sourceTool={}, parameterKeys={}, contextKeys={}, protocolTrace={}, repairs={}, valid={}",
            toolName, requestedTemplateId, templateId, trace.getToolName(),
            mutableMap(values.get("parameters")).keySet(),
            mutableMap(values.get("executionContext")).keySet(), protocolTrace, repairs, valid);
    }

    /**
     * Applies a tool-published dependency-evidence adapter contract outside the
     * InterpretationPlan DAG path. Mandatory workflow recovery still has ordered
     * predecessor traces, so it must transport those successful structured outputs
     * instead of falling back to a query-only invocation.
     */
    public Map<String, Object> applyPublishedDependencyEvidenceContract(
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

    /**
     * Applies every deterministic predecessor-evidence adapter needed by workflow
     * recovery. The ordering is intentional: generic published evidence is attached
     * first, then a template executor is compiled from the observed discovery result.
     */
    public Map<String, Object> applyDeterministicDependencyContracts(
        String toolName,
        Map<String, Object> arguments,
        List<InteractionToolTrace> dependencyTraces,
        String userQuery
    ) {
        Map<String, Object> resolved = applyPublishedDependencyEvidenceContract(
            toolName, arguments, dependencyTraces);
        // A template discovery trace may be an earlier workflow predecessor of an
        // independent tool (for example metadata lookup). Only tools whose own
        // published contract says that they consume templates may be compiled or
        // rejected against template candidates.
        if (!requiresObservedTemplateContract(toolName)) {
            return resolved;
        }
        Map<String, Object> batch = applyObservedTemplateSetContract(
            toolName, resolved, dependencyTraces, userQuery);
        if (batch != null) {
            return batch;
        }
        String incompatibility = observedTemplateExecutorIncompatibility(toolName, dependencyTraces);
        if (incompatibility != null) {
            Map<String, Object> denied = new LinkedHashMap<>(resolved);
            denied.put(McpParamBindingResolver.STATUS_KEY, "DENIED");
            denied.put(McpParamBindingResolver.CODE_KEY, "NO_COMPATIBLE_TEMPLATE_EXECUTOR");
            denied.put(McpParamBindingResolver.ERROR_KEY, incompatibility);
            return denied;
        }
        return applyObservedTemplateContract(toolName, resolved, dependencyTraces, userQuery);
    }

    private boolean requiresObservedTemplateContract(String toolName) {
        if (toolName == null || toolName.isBlank()
            || toolNames.isAssetDiscoveryToolName(toolName)
            || toolNames.isTemplateDiscoveryToolName(toolName)) {
            return false;
        }
        // The normalized workflow role is the authoritative execution contract.
        // MCP transports are allowed to omit optional extension metadata, but that
        // must not make a declared template executor lose its discovery binding.
        if (workflowRole(toolName) == ToolWorkflowRole.TEMPLATE_EXECUTION) {
            return true;
        }
        // Legacy/unit registries did not publish applicability metadata. Preserve
        // their fail-closed behaviour; production MCP tools publish the contract.
        if (toolRegistry == null) {
            return true;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata == null) {
            return true;
        }
        Map<String, Object> extra = metadata.getMetadata() == null
            ? Map.of() : metadata.getMetadata();
        Map<String, Object> mcpMeta = mutableMap(extra.get("mcpToolMeta"));
        if (booleanValue(mcpMeta.get("doesNotExecuteSql"))) {
            return false;
        }
        if (booleanValue(mcpMeta.get("templateRegistrySupported"))) {
            return true;
        }
        Map<String, Object> applicability = mutableMap(mcpMeta.get("applicability"));
        String scope = scalarText(firstPresent(applicability, "scopeLabel", "scope_label"));
        if (scope != null) {
            String normalized = scope.toLowerCase(Locale.ROOT);
            if (normalized.contains("template_execution") || normalized.contains("script_execution")) {
                return true;
            }
            if (normalized.contains("discovery") || normalized.contains("search")) {
                return false;
            }
        }
        if (metadata.getParameters() != null && metadata.getParameters().stream()
            .filter(Objects::nonNull)
            .map(parameter -> parameter.getName() == null ? "" : parameter.getName())
            .map(name -> name.replace("_", "").toLowerCase(Locale.ROOT))
            .anyMatch(name -> "template".equals(name) || "templateid".equals(name)
                || "commandtemplate".equals(name))) {
            return true;
        }
        return false;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool
            : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String observedTemplateExecutorIncompatibility(
        String toolName,
        List<InteractionToolTrace> traces
    ) {
        if (toolName == null || traces == null || traces.isEmpty()) {
            return null;
        }
        for (int index = traces.size() - 1; index >= 0; index--) {
            InteractionToolTrace trace = traces.get(index);
            if (trace == null || !trace.isSuccess() || !templateDiscoveryTool(trace.getToolName())
                || trace.getOutput() == null || trace.getOutput().isBlank()) {
                continue;
            }
            Object output = parseJson(trace.getOutput());
            List<Map<String, Object>> templates = discoveredTemplates(output);
            if (templates.isEmpty()) {
                continue;
            }
            if (templates.stream().anyMatch(template -> sameExecutor(toolName, discoveredExecutor(template)))) {
                return null;
            }
            List<String> declaredExecutors = templates.stream()
                .map(this::discoveredExecutor)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
            return "Template discovery " + trace.getToolName() + " returned " + templates.size()
                + " admitted template(s), but none declares executor " + toolName
                + ". Declared executors: " + declaredExecutors + ".";
        }
        return null;
    }

    /**
     * Compiles only the explicitly reviewed template admission set when deterministic workflow
     * recovery has no model-selected scalar template. An unreviewed business-group result is a
     * high-recall catalog and must never be converted into physical calls wholesale.
     */
    private Map<String, Object> applyObservedTemplateSetContract(
        String toolName,
        Map<String, Object> arguments,
        List<InteractionToolTrace> traces,
        String userQuery
    ) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (traces == null || traces.isEmpty() || batchCalls(values) != null) {
            return null;
        }
        for (int traceIndex = traces.size() - 1; traceIndex >= 0; traceIndex--) {
            InteractionToolTrace trace = traces.get(traceIndex);
            if (trace == null || !trace.isSuccess() || trace.getOutput() == null || trace.getOutput().isBlank()) {
                continue;
            }
            Object output = parseJson(trace.getOutput());
            if (output == null) {
                continue;
            }
            Set<String> reviewedTemplateIds = reviewedTemplateIds(output);
            if (reviewedTemplateIds.isEmpty()) {
                continue;
            }
            Map<String, Map<String, Object>> uniqueCandidates = new LinkedHashMap<>();
            for (Map<String, Object> template : discoveredTemplates(output)) {
                String templateId = templateId(template);
                if (templateId != null
                    && reviewedTemplateIds.contains(templateId.toLowerCase(Locale.ROOT))
                    && sameExecutor(toolName, discoveredExecutor(template))) {
                    uniqueCandidates.putIfAbsent(templateId.toLowerCase(Locale.ROOT), template);
                }
            }
            if (uniqueCandidates.size() < 2) {
                continue;
            }
            List<Map<String, Object>> calls = new ArrayList<>();
            List<String> compilationFailures = new ArrayList<>();
            int callIndex = 1;
            for (Map<String, Object> template : uniqueCandidates.values()) {
                String templateId = templateId(template);
                Map<String, Object> candidateInput = new LinkedHashMap<>(values);
                // The reviewed discovery set is authoritative in recovery. Remove any
                // planner-authored scalar (including literal placeholders) before binding
                // this child to the published template contract.
                for (String key : List.of(
                    "template", "templateId", "template_id",
                    "commandTemplate", "command_template")) {
                    candidateInput.remove(key);
                }
                if (usesTemplateIdField(toolName)) {
                    candidateInput.put("templateId", templateId);
                } else {
                    candidateInput.put("template", templateId);
                }
                try {
                    TemplateInvocationBridge.BridgeResult bridged = TEMPLATE_INVOCATION_BRIDGE.prepare(
                        new TemplateInvocationBridge.BridgeRequest(
                            toolName, null, templateId, template, candidateInput,
                            parameterProtocol(candidateInput), false, true,
                            new TemplateInvocationBridge.EvidenceContext(userQuery, Map.of())
                        )
                    );
                    Map<String, Object> compiledInput = new LinkedHashMap<>(bridged.executorInput());
                    boolean templateBoundContext = mergeObservedExecutionContext(compiledInput, template);
                    finishObservedTemplateInput(toolName, compiledInput, output, templateBoundContext);
                    putRuntimeTemplateBinding(compiledInput, templateId, toolName,
                        "reviewed_template_discovery_agent_batch");
                    calls.add(Map.of(
                        "callId", "template-" + callIndex++,
                        "toolName", toolName,
                        "arguments", compiledInput
                    ));
                } catch (TemplateInvocationBridge.TemplateBridgeException failure) {
                    compilationFailures.add(templateId + "[" + failure.code() + "]: "
                        + failure.getMessage());
                }
            }
            if (calls.size() != uniqueCandidates.size()) {
                Map<String, Object> denied = new LinkedHashMap<>();
                denied.put(McpParamBindingResolver.STATUS_KEY, "DENIED");
                denied.put(McpParamBindingResolver.CODE_KEY,
                    "REVIEWED_TEMPLATE_BATCH_CARDINALITY_MISMATCH");
                denied.put(McpParamBindingResolver.ERROR_KEY,
                    "Reviewed template batch must preserve the complete admission set; selectedCount="
                        + uniqueCandidates.size() + ", compiledCount=" + calls.size()
                        + ", failures=" + compilationFailures);
                log.warn("Agent deterministic workflow rejected incomplete admitted template batch: "
                        + "tool={}, sourceTool={}, selectedCount={}, compiledCount={}, failures={}",
                    toolName, trace.getToolName(), uniqueCandidates.size(), calls.size(), compilationFailures);
                return denied;
            }
            // A batch executor consumes only its compiled calls and batch controls.
            // Do not retain planner-authored scalar template references, execution
            // context, or parameters at the envelope level: child calls below are
            // the metadata-validated authoritative requests.
            Map<String, Object> batch = new LinkedHashMap<>();
            batch.put("executionMode", "SEQUENTIAL");
            batch.put("stopOnFailure", false);
            batch.put("calls", List.copyOf(calls));
            batch.put(RUNTIME_OWNED_TEMPLATE_BATCH_MARKER, true);
            log.info("Agent deterministic workflow compiled admitted template set: tool={}, sourceTool={}, callCount={}",
                toolName, trace.getToolName(), calls.size());
            return batch;
        }
        return null;
    }

    private void putRuntimeTemplateBinding(Map<String, Object> input,
                                           String templateId,
                                           String executorTool,
                                           String source) {
        if (input == null || templateId == null || templateId.isBlank()
            || executorTool == null || executorTool.isBlank()) {
            return;
        }
        input.put(McpTemplateBindingEvidence.CONTEXT_KEY, new McpTemplateBindingEvidence(
            McpTemplateBindingEvidence.SCHEMA_VERSION, source, templateId, executorTool).toMap());
    }

    private Set<String> reviewedTemplateIds(Object value) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectReviewedTemplateIds(value, ids, 0);
        return Set.copyOf(ids);
    }

    @SuppressWarnings("unchecked")
    private void collectReviewedTemplateIds(Object value, Set<String> ids, int depth) {
        if (value == null || depth > 8) return;
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectReviewedTemplateIds(item, ids, depth + 1));
            return;
        }
        if (!(value instanceof Map<?, ?> raw)) return;
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        Object selection = firstPresent(map, "runtimeTemplateSelection", "templateMatchAnalysis",
            "businessTemplateRequirementMatch");
        if (selection instanceof Map<?, ?> rawSelection) {
            Object selected = firstPresent(mutableMap(rawSelection),
                "selectedTemplateIds", "selected_template_ids", "selectedIds");
            if (selected instanceof Iterable<?> iterable) {
                for (Object id : iterable) {
                    String text = scalarText(id);
                    if (text != null) ids.add(text.toLowerCase(Locale.ROOT));
                }
            }
        }
        map.values().forEach(item -> collectReviewedTemplateIds(item, ids, depth + 1));
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
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> template : templates) {
            String id = templateId(template);
            String executor = discoveredExecutor(template);
            if (id == null || executor == null) continue;
            String key = id.toLowerCase(Locale.ROOT) + "::" + executor.toLowerCase(Locale.ROOT);
            Map<String, Object> current = unique.get(key);
            if (current == null || templateContractQuality(template) > templateContractQuality(current)) {
                unique.put(key, template);
            }
        }
        return List.copyOf(unique.values());
    }

    private int templateContractQuality(Map<String, Object> template) {
        if (template == null || template.isEmpty()) return 0;
        int score = template.size();
        if (firstPresent(template, "parameterSchema", "parameter_schema", "inputSchema", "schema")
            instanceof Map<?, ?>) score += 100;
        if (nested(template, "executionBinding", "toolName") != null
            || nested(template, "sqlExecutionBinding", "toolName") != null
            || nested(template, "execution", "executorTool") != null) score += 80;
        if (nested(template, "executionBinding", "executionContext") != null
            || nested(template, "sqlExecutionBinding", "executionContext") != null
            || template.get("executionContext") instanceof Map<?, ?>) score += 60;
        if (firstPresent(template, "executionArguments", "execution_arguments") instanceof Map<?, ?>) score += 40;
        return score;
    }

    @SuppressWarnings("unchecked")
    private void collectDiscoveredTemplates(Object value, List<Map<String, Object>> templates, int depth) {
        collectDiscoveredTemplates(value, templates, depth, null);
    }

    @SuppressWarnings("unchecked")
    private void collectDiscoveredTemplates(Object value, List<Map<String, Object>> templates,
                                             int depth, String inheritedExecutor) {
        if (value == null || depth > 8) {
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectDiscoveredTemplates(item, templates, depth + 1, inheritedExecutor));
            return;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        String executor = firstNonBlank(discoveredExecutor(map), inheritedExecutor);
        Object templateId = firstPresent(map, "templateId", "template_id", "id", "code");
        if (scalarText(templateId) != null && executor != null) {
            Map<String, Object> candidate = new LinkedHashMap<>(map);
            candidate.putIfAbsent("executionTool", executor);
            templates.add(candidate);
            return;
        }
        for (String key : List.of("templates", "candidates", "associatedTemplates", "associated_templates",
            "searchResult", "hits", "document", "item", "results", "items", "data", "result", "payload",
            "structuredContent", "structured_content", "routingProjection")) {
            collectDiscoveredTemplates(map.get(key), templates, depth + 1, executor);
        }
    }

    private TemplateResolutionEvent templateResolutionEvent(
        TemplateInvocationBridge.TemplateBridgeException failure,
        String templateId,
        Map<String, Object> template,
        String searchTool
    ) {
        if (templateId == null || templateId.isBlank() || "TEMPLATE_REQUIRED".equals(failure.code())) {
            return TemplateResolutionEvent.missingId(null, searchTool);
        }
        List<String> missing = failure.validationErrors().stream()
            .filter(error -> error != null && ("REQUIRED_PARAMETER_MISSING".equals(error.errorCode())
                || String.valueOf(error.message()).toLowerCase(Locale.ROOT).contains("required")))
            .map(ToolArgumentCompiler.ValidationError::field)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (missing.isEmpty()) {
            Object declared = nested(template == null ? Map.of() : template, "parameterSchema", "required");
            if (declared instanceof List<?> values) {
                missing = values.stream().map(this::scalarText).filter(Objects::nonNull).distinct().toList();
            }
        }
        if (!missing.isEmpty() || String.valueOf(failure.code()).contains("PARAMETER")) {
            return TemplateResolutionEvent.missingParameters(null, templateId, missing);
        }
        return TemplateResolutionEvent.notFound(null, templateId, searchTool);
    }

    private String discoveredExecutor(Map<String, Object> template) {
        return firstNonBlank(
            scalarText(firstPresent(template, "executionTool", "execution_tool", "executorTool", "executor_tool")),
            scalarText(nested(template, "parameterContract", "executionTool")),
            scalarText(nested(template, "parameter_contract", "execution_tool")),
            scalarText(nested(template, "invocationExample", "tool")),
            scalarText(nested(template, "execution", "executorTool")),
            scalarText(nested(template, "executionBinding", "toolName")),
            scalarText(nested(template, "sqlExecutionBinding", "toolName"))
        );
    }

    private String runtimeScalarText(Object value) {
        String text = scalarText(value);
        return isUnresolvedRuntimeReference(text) ? null : text;
    }

    private boolean isUnresolvedRuntimeReference(String text) {
        if (text == null) {
            return true;
        }
        String normalized = text.trim();
        return normalized.startsWith("$.") || normalized.startsWith("$[")
            || normalized.contains("${") || normalized.contains("{{")
            || (normalized.startsWith("<") && normalized.endsWith(">"));
    }

    private boolean usesTemplateIdField(String toolName) {
        ToolMetadata metadata = toolRegistry == null || toolName == null
            ? null : toolRegistry.getToolMetadata(toolName);
        if (metadata != null && metadata.getParameters() != null) {
            boolean acceptsTemplateId = metadata.getParameters().stream()
                .filter(Objects::nonNull)
                .map(parameter -> parameter.getName() == null ? "" : parameter.getName())
                .anyMatch(name -> "templateid".equals(name.replace("_", "").toLowerCase(Locale.ROOT)));
            if (acceptsTemplateId) return true;
            boolean acceptsTemplate = metadata.getParameters().stream()
                .filter(Objects::nonNull)
                .map(parameter -> parameter.getName() == null ? "" : parameter.getName())
                .anyMatch(name -> "template".equals(name.replace("_", "").toLowerCase(Locale.ROOT)));
            if (acceptsTemplate) return false;
        }
        return conventionalTemplateIdExecutor(toolName);
    }

    private boolean sameExecutor(String actualTool, String declaredExecutor) {
        if (actualTool == null || declaredExecutor == null) {
            return false;
        }
        String actual = actualTool.trim().toLowerCase(Locale.ROOT);
        String declared = declaredExecutor.trim().toLowerCase(Locale.ROOT);
        return actual.equals(declared) || actual.endsWith("_" + declared);
    }

    private boolean conventionalTemplateIdExecutor(String toolName) {
        if (toolName == null) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return "template_execute".equals(normalized) || normalized.endsWith("_template_execute");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> selectedAsset(Object output) {
        if (!(output instanceof Map<?, ?> root)) {
            return Map.of();
        }
        Object selected = nested((Map<String, Object>) root, "queryIr", "asset", "selected");
        if (selected == null) {
            selected = nested((Map<String, Object>) root,
                "routingProjection", "queryIr", "asset", "selected");
        }
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

    private void putIfTextAbsent(Map<String, Object> target, String key, Object value) {
        if (target.containsKey(key)) {
            return;
        }
        putIfText(target, key, value);
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

    private Object firstPresent(Map<?, ?> values, String... keys) {
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
