package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.toolcall.ContextualToolArgumentResolver;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model proposal boundary for missing direct-tool parameters; Runtime verifies all pointers. */
@Slf4j
class ModelAssistedContextParameterBridge {

    private static final int MAX_EVIDENCE_CHARS = 24_000;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    ModelAssistedContextParameterBridge(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    Map<String, Object> propose(ChatModel model,
                                String toolName,
                                Map<String, Object> arguments,
                                ModelAssistedRetrievalBridge.RetrievalEvidenceContext evidence) {
        Map<String, Object> result = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        ToolWorkflowRole workflowRole = toolRegistry == null || toolName == null
            ? ToolWorkflowRole.DIRECT : toolRegistry.getWorkflowRole(toolName);
        if (model == null || toolRegistry == null || toolName == null
            || workflowRole == ToolWorkflowRole.ASSET_DISCOVERY
            || workflowRole == ToolWorkflowRole.TEMPLATE_DISCOVERY
            || workflowRole == ToolWorkflowRole.TEMPLATE_EXECUTION) return result;
        Map<String, Object> schema = schema(toolRegistry.getToolMetadata(toolName));
        List<String> missing = required(schema).stream()
            .filter(field -> !meaningful(result.get(field)))
            .toList();
        if (missing.isEmpty()) return result;
        ModelAssistedRetrievalBridge.RetrievalEvidenceContext trusted = evidence == null
            ? new ModelAssistedRetrievalBridge.RetrievalEvidenceContext(null, Map.of()) : evidence;
        if ((trusted.userQuery() == null || trusted.userQuery().isBlank())
            && trusted.completedStepOutputs().isEmpty()) return result;
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("userQuery", trusted.userQuery());
            context.put("completedStepOutputs", trusted.completedStepOutputs());
            String serialized = objectMapper.writeValueAsString(context);
            if (serialized.length() > MAX_EVIDENCE_CHARS) {
                serialized = serialized.substring(0, MAX_EVIDENCE_CHARS);
            }
            String prompt = """
                You propose evidence pointers for missing tool arguments. Return JSON only:
                {"evidence":[{"parameter":"...","source":"completed_step","stepId":1,"outputPath":"$.path"}]}
                or source=user_query with quote and value. Never invent a value or field. Use only the supplied
                published schema and evidence. A completed_step proposal must point to the exact scalar value.
                If evidence is absent or ambiguous, omit that parameter.
                Tool: %s
                Missing required fields: %s
                Published properties: %s
                Runtime evidence: %s
                """.formatted(toolName, missing, schema.get("properties"), serialized);
            Map<String, Object> response = parseObject(model.chat(prompt));
            Object proposals = response.get("evidence");
            if (proposals instanceof List<?> list && !list.isEmpty()) {
                result.put(ContextualToolArgumentResolver.MODEL_EVIDENCE_FIELD, list);
                log.info("Model proposed contextual parameter evidence tool={} missing={} proposalCount={}",
                    toolName, missing, list.size());
            }
        } catch (Exception ex) {
            log.warn("Context parameter proposal fell back to deterministic Runtime recovery tool={} reason={}",
                toolName, ex.getMessage());
        }
        return result;
    }

    private Map<String, Object> schema(ToolMetadata metadata) {
        if (metadata == null) return Map.of();
        if (metadata.getMetadata() != null
            && metadata.getMetadata().get("inputSchema") instanceof Map<?, ?> inputSchema) {
            return stringMap(inputSchema);
        }
        if (metadata.getParameters() == null || metadata.getParameters().isEmpty()) return Map.of();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameter parameter : metadata.getParameters()) {
            if (parameter == null || parameter.getName() == null) continue;
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", parameter.getType() == null ? "string" : parameter.getType());
            if (parameter.getMetadata() != null) {
                for (String key : List.of("aliases", "acceptedSources", "format")) {
                    if (parameter.getMetadata().containsKey(key)) {
                        property.put(key, parameter.getMetadata().get(key));
                    }
                }
            }
            properties.put(parameter.getName(), property);
            if (parameter.isRequired()) required.add(parameter.getName());
        }
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private List<String> required(Map<String, Object> schema) {
        Object value = schema.get("required");
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> fields = new ArrayList<>();
        iterable.forEach(item -> { if (item != null) fields.add(String.valueOf(item)); });
        return fields;
    }

    private Map<String, Object> parseObject(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) return Map.of();
        return objectMapper.readValue(text.substring(start, end + 1), new TypeReference<>() {});
    }

    private boolean meaningful(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> { if (key != null) result.put(String.valueOf(key), value); });
        return result;
    }
}
