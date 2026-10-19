package com.chatchat.api.runtime.coding;

import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.common.runtime.coding.CodingRuntimeContext;
import java.time.Instant;
import java.util.*;

/** Captures the existing run configuration; never resolves secrets or introduces a second catalog. */
public final class CodingConfigurationSnapshot {
    private CodingConfigurationSnapshot() { }
    public static void requireSameModel(Map<String,Object> frozen, Map<String,Object> attributes, Map<String,Object> metadata) {
        if (!frozen.isEmpty() && !modelIdentity(frozen.get("model")).equals(modelIdentity(capture(attributes, metadata).get("model"))))
            throw new IllegalStateException("Coding recovery requires the original authorized model configuration; a new configuration needs a new task");
    }
    private static String modelIdentity(Object value) {
        var sorted = new TreeMap<String,Object>();
        if (value instanceof Map<?,?> map) map.forEach((key,item) -> sorted.put(String.valueOf(key),item));
        // JSON persistence can restore a small Long as Integer; this is the same effective parameter.
        return com.chatchat.agents.protocol.ModelProtocolJson.compact(sorted);
    }
    public static Map<String, Object> capture(Map<String, Object> attributes, Map<String, Object> metadata) {
        var model = new TreeMap<String, Object>();
        if (attributes.get("checkpointModelConfig") instanceof Map<?, ?> configured) {
            for (String key : List.of("selectedModel", "providerModel", "protocol", "implementation", "timeout", "maxTokens", "maxRetries")) {
                Object value = configured.get(key);
                if (value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Boolean)
                    model.put(key, value);
            }
        }
        if (!model.containsKey("selectedModel") && metadata.get("modelName") instanceof String name && !name.isBlank())
            model.put("selectedModel", name);
        // Absence is represented explicitly, never replaced with a guessed default model.
        model.put("binding", "AUTHORIZED_PARENT_MODEL");
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "runtime_os.coding.context.v1");
        result.put("capturedAt", Instant.now().toString());
        result.put("consistency", "TASK_SNAPSHOT");
        result.put("model", model);
        result.put("skills", SkillAnalysisContext.from(attributes));
        Object configured = attributes.get(com.chatchat.common.skills.DomainSkillRuntimePort.PLANNING_CONTEXT_ATTRIBUTE);
        result.put("configuredSkillIds", configured instanceof Map<?, ?> map && map.get("configuredSkillIds") instanceof List<?> ids
            ? ids.stream().filter(String.class::isInstance).limit(128).toList() : List.of());
        return CodingRuntimeContext.validate(result);
    }
}
