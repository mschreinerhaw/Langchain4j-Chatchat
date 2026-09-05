package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol;
import com.chatchat.common.tool.DataAnalysisContextProtocol;
import com.chatchat.common.runtime.summary.analysis.semantic.adapter.ProducerSemanticDeclarationProtocol;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts trusted MCP tool-definition metadata and returned analysis metadata into the
 * source-neutral summary-governance contract. It deliberately extracts an analytical
 * allow-list instead of copying runtime identity, credentials, or arbitrary MCP metadata.
 */
public final class McpAnalysisContextAdapter implements RuntimeAnalysisContextProtocol {

    private static final List<String> CONTEXT_PATHS = List.of(
        "structuredContent", "structured_content", "payload", "result", "data");
    private static final List<String> MCP_EXTENSION_KEYS = List.of(
        "serviceId", "remoteToolName", "providerModule", "mcpCapabilityCode",
        "assetType", "templateId", "kind", "schemaVersion");

    private final ObjectMapper objectMapper;

    public McpAnalysisContextAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /** Returns the merged tool-definition and root-result context. */
    public Map<String, Object> adapt(String reference, ToolMetadata metadata, Object output) {
        Object payload = output instanceof ToolOutput toolOutput ? toolOutput.getData() : output;
        Map<String, Object> returnedContext = withoutRuntimeContracts(returnedContext(payload));
        Map<String, Object> toolContext = isMcp(metadata) ? toolContext(reference, metadata) : Map.of();
        return immutable(withoutRuntimeContracts(deepMerge(toolContext, returnedContext)));
    }

    /**
     * Applies root-level shared semantics to a child dataset and then overlays the child's own
     * declaration. Child metadata therefore wins without losing shared capability or scope.
     */
    public Map<String, Object> adaptDataset(Map<String, Object> rootContext,
                                            Map<String, Object> dataset) {
        Map<String, Object> child = returnedContext(dataset);
        child = deepMerge(child, contextSections(dataset));
        child = withoutRuntimeContracts(child);
        return immutable(withoutRuntimeContracts(deepMerge(rootContext, child)));
    }

    /** Returned data cannot install or replace executable analysis recipes. */
    private Map<String, Object> withoutRuntimeContracts(Map<String, Object> context) {
        if (context == null || context.isEmpty()) return Map.of();
        Map<String, Object> sanitized = new LinkedHashMap<>(context);
        Map<String, Object> extensions = new LinkedHashMap<>(map(sanitized.get("extensions")));
        extensions.remove("deterministicInsights");
        extensions.remove("deterministic_insights");
        if (extensions.isEmpty()) sanitized.remove("extensions");
        else sanitized.put("extensions", extensions);
        return sanitized;
    }

    private Map<String, Object> toolContext(String reference, ToolMetadata metadata) {
        Map<String, Object> extra = map(metadata.getMetadata());
        Map<String, Object> mcpMeta = map(extra.get("mcpToolMeta"));

        Map<String, Object> source = new LinkedHashMap<>();
        put(source, "type", "mcp_tool");
        put(source, "runtimeReference", reference);
        put(source, "id", first(metadata.getId(), text(extra.get("remoteToolName"))));
        put(source, "displayName", first(metadata.getTitle(), metadata.getId()));
        put(source, "toolDisplayName", first(metadata.getTitle(), metadata.getId()));
        put(source, "description", metadata.getDescription());
        put(source, "provider", first(metadata.getAuthor(), text(extra.get("providerModule"))));
        put(source, "serviceId", text(extra.get("serviceId")));
        put(source, "remoteToolName", text(extra.get("remoteToolName")));

        Map<String, Object> capability = new LinkedHashMap<>();
        put(capability, "code", first(text(extra.get("mcpCapabilityCode")), metadata.getCategory()));
        put(capability, "categories", metadata.getCategories());
        put(capability, "tags", metadata.getTags());
        capability = deepMerge(capability, firstMap(mcpMeta, extra,
            "capability", "capabilitySpec", "capability_spec"));
        Object applicability = firstValue(mcpMeta, extra, "applicability");
        put(capability, "applicability", applicability);

        Map<String, Object> business = new LinkedHashMap<>();
        put(business, "category", metadata.getCategory());
        put(business, "categories", metadata.getCategories());
        put(business, "tags", metadata.getTags());
        business = deepMerge(business, firstMap(mcpMeta, extra,
            "business", "businessGroup", "business_group"));

        Map<String, Object> schema = new LinkedHashMap<>();
        put(schema, "outputType", metadata.getOutputType());
        Object definition = firstValue(mcpMeta, extra,
            "outputSchema", "output_schema", "outputSchemaJson");
        put(schema, "definition", parsed(definition));
        Object fields = firstValue(mcpMeta, extra,
            "fieldMetadata", "field_metadata", "columnMetadata", "column_metadata");
        put(schema, "fields", parsed(fields));

        Object relationships = firstValue(mcpMeta, extra,
            "relationships", "dependencySpec", "dependency_spec", "dependencySpecJson");
        Map<String, Object> semantics = firstMap(mcpMeta, extra,
            "semantics", "analysisSemantics", "analysis_semantics");
        Map<String, Object> quality = firstMap(mcpMeta, extra,
            "quality", "dataQuality", "data_quality");
        Map<String, Object> analysisPolicy = firstMap(mcpMeta, extra,
            "analysisPolicy", "analysis_policy");
        Map<String, Object> extensions = firstMap(mcpMeta, extra, "extensions");
        Map<String, Object> mcpExtension = new LinkedHashMap<>();
        for (String key : MCP_EXTENSION_KEYS) {
            Object value = mcpMeta.containsKey(key) ? mcpMeta.get(key) : extra.get(key);
            put(mcpExtension, key, value);
        }
        if (!mcpExtension.isEmpty()) extensions = deepMerge(extensions, Map.of("mcp", mcpExtension));

        Map<String, Object> declared = firstMap(mcpMeta, extra,
            "analysisContext", "analysis_context");
        Map<String, Object> generated = DataAnalysisContextProtocol.create(
            source, capability, business, schema, parsed(relationships), semantics, quality,
            analysisPolicy, extensions);
        Object producerDeclaration = firstValue(mcpMeta, extra,
            ProducerSemanticDeclarationProtocol.CONTEXT_KEY, "producer_semantic_declaration");
        if (producerDeclaration != null) {
            generated = ProducerSemanticDeclarationProtocol.mergeIntoAnalysisContext(
                generated, parsed(producerDeclaration));
        }
        return deepMerge(generated, declared);
    }

    private Map<String, Object> returnedContext(Object value) {
        Map<String, Object> root = map(value);
        if (root.isEmpty()) return Map.of();
        Map<String, Object> direct = firstMap(root, Map.of(), "analysisContext", "analysis_context");
        Map<String, Object> commandContext = firstMap(root, Map.of(), "commandContext", "command_context");
        for (String path : CONTEXT_PATHS) {
            Map<String, Object> nested = map(root.get(path));
            Map<String, Object> context = firstMap(nested, Map.of(), "analysisContext", "analysis_context");
            if (!context.isEmpty()) direct = deepMerge(direct, context);
            Map<String, Object> nestedCommandContext = firstMap(
                nested, Map.of(), "commandContext", "command_context");
            if (!nestedCommandContext.isEmpty()) {
                commandContext = deepMerge(commandContext, nestedCommandContext);
            }
        }
        return deepMerge(commandAnalysisContext(commandContext), direct);
    }

    private Map<String, Object> commandAnalysisContext(Map<String, Object> commandContext) {
        if (commandContext == null || commandContext.isEmpty()) return Map.of();
        Map<String, Object> source = new LinkedHashMap<>();
        put(source, "id", text(commandContext.get("templateId")));
        put(source, "displayName", text(commandContext.get("templateName")));
        put(source, "description", text(commandContext.get("description")));

        Map<String, Object> capability = new LinkedHashMap<>();
        put(capability, "executionMode", commandContext.get("executionMode"));
        put(capability, "commands", commandContext.get("commands"));

        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("commandContext", commandContext);
        return DataAnalysisContextProtocol.create(
            source, capability, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), extensions);
    }

    /** Maps explicit dataset-level semantic sections without copying returned row values. */
    private Map<String, Object> contextSections(Map<String, Object> dataset) {
        if (dataset == null || dataset.isEmpty()) return Map.of();
        Map<String, Object> context = new LinkedHashMap<>();
        copySection(dataset, context, "source");
        copySection(dataset, context, "capability");
        copySection(dataset, context, "business");
        copySection(dataset, context, "schema");
        copySection(dataset, context, "relationships");
        copySection(dataset, context, "semantics");
        copySection(dataset, context, "quality");
        copySection(dataset, context, "analysisPolicy");
        copySection(dataset, context, "extensions");
        return context;
    }

    private void copySection(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) target.put(key, source.get(key));
    }

    private boolean isMcp(ToolMetadata metadata) {
        if (metadata == null) return false;
        if (metadata.getCategories() != null
            && metadata.getCategories().stream().anyMatch(value -> "mcp".equalsIgnoreCase(value))) return true;
        Map<String, Object> extra = map(metadata.getMetadata());
        return Boolean.TRUE.equals(extra.get("mcpCapability"))
            || extra.containsKey("serviceId") || extra.containsKey("mcpToolMeta");
    }

    private Object firstValue(Map<String, Object> primary,
                              Map<String, Object> secondary,
                              String... keys) {
        for (String key : keys) {
            if (primary != null && primary.get(key) != null) return primary.get(key);
            if (secondary != null && secondary.get(key) != null) return secondary.get(key);
        }
        return null;
    }

    private Map<String, Object> firstMap(Map<String, Object> primary,
                                         Map<String, Object> secondary,
                                         String... keys) {
        return map(firstValue(primary, secondary, keys));
    }

    private Object parsed(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return value;
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return value;
        try {
            return objectMapper.readValue(trimmed, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    private Map<String, Object> map(Object value) {
        Object parsed = parsed(value);
        if (!(parsed instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> parent, Map<String, Object> child) {
        Map<String, Object> merged = new LinkedHashMap<>(parent == null ? Map.of() : parent);
        if (child == null) return merged;
        child.forEach((key, childValue) -> {
            Object parentValue = merged.get(key);
            if (parentValue instanceof Map<?, ?> parentMap && childValue instanceof Map<?, ?> childMap) {
                merged.put(key, deepMerge(map(parentMap), map(childMap)));
            } else if (childValue != null) {
                merged.put(key, childValue);
            }
        });
        return merged;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value == null) return;
        if (value instanceof String text && text.isBlank()) return;
        if (value instanceof List<?> list && list.isEmpty()) return;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return;
        target.put(key, value);
    }

    private String first(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Map<String, Object> immutable(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
