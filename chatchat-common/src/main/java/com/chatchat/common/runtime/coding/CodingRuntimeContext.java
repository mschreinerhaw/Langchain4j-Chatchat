package com.chatchat.common.runtime.coding;

import java.util.*;

/** Public, secret-free configuration snapshot. It grants no permissions beyond Authorization. */
public final class CodingRuntimeContext {
    private CodingRuntimeContext() { }
    public static Map<String, Object> validate(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        CodingProtocol.require(value.keySet().stream().allMatch(Set.of("schemaVersion", "capturedAt",
            "consistency", "model", "skills", "configuredSkillIds", "inputFiles", "executionProfile")::contains), "Unknown Coding context field");
        if(value.containsKey("executionProfile")) {
            CodingProtocol.require(value.get("executionProfile") instanceof Map<?,?>,"Coding execution profile required");
            var profile=(Map<?,?>)value.get("executionProfile");
            CodingProtocol.require(profile.keySet().stream().allMatch(Set.of("schemaVersion","complexity","sourceCount","inputRows","selectedModel","providerModel","protocol","implementation","reasoningEffort")::contains)
                && "runtime_os.coding.execution_profile.v1".equals(profile.get("schemaVersion"))
                && profile.get("complexity") instanceof String complexity && Set.of("SIMPLE","COMPLEX").contains(complexity)
                && profile.get("sourceCount") instanceof Number sources && sources.longValue()>=1 && sources.longValue()<=CodingProtocol.MAX_ANALYSIS_SOURCES
                && profile.get("inputRows") instanceof Number rows && rows.longValue()>=0,
                "Invalid Coding execution profile");
            if(profile.containsKey("selectedModel"))CodingProtocol.require(profile.get("selectedModel") instanceof String alias
                && alias.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                && profile.containsKey("providerModel") && profile.containsKey("protocol")
                && profile.containsKey("implementation"),"Invalid Coding model alias or unfrozen provider identity");
            for(String key:List.of("providerModel","protocol","implementation"))
                if(profile.containsKey(key))CodingProtocol.require(profile.get(key) instanceof String text && text.length()<=512,
                    "Invalid Coding provider identity");
            if(profile.containsKey("reasoningEffort"))CodingProtocol.require(profile.get("reasoningEffort") instanceof String effort
                && Set.of("none","minimal","low","medium","high","xhigh").contains(effort),"Invalid Coding reasoning effort");
        }
        if(value.containsKey("inputFiles")) {
            CodingProtocol.require(value.get("inputFiles") instanceof Map<?,?>,"Input capability descriptor required");
            var input=(Map<?,?>)value.get("inputFiles");
            CodingProtocol.require(input.keySet().equals(Set.of("schemaVersion","format","delivery","manifestFileId","manifestSha256"))
                && CodingInputManifest.VERSION.equals(input.get("schemaVersion")) && "SHARED_DIRECTORY".equals(input.get("delivery"))
                && Set.of("PARQUET","CSV").contains(input.get("format")),"Unsupported input capability descriptor");
            CodingInputManifest.fileId(String.valueOf(input.get("manifestFileId")));CodingInputManifest.hash(String.valueOf(input.get("manifestSha256")));
        }
        CodingProtocol.require("runtime_os.coding.context.v1".equals(value.get("schemaVersion")), "Unknown Coding context version");
        CodingProtocol.require("TASK_SNAPSHOT".equals(value.get("consistency")), "Coding context must be task scoped");
        return freeze(value, 0);
    }
    private static Map<String, Object> freeze(Map<?, ?> value, int depth) {
        CodingProtocol.require(depth < 12 && value.size() <= 128, "Coding context is too large");
        Map<String, Object> result = new TreeMap<>();
        value.forEach((key, item) -> {
            CodingProtocol.require(key instanceof String, "Coding context keys must be strings");
            String name = (String) key;
            CodingProtocol.require(!name.toLowerCase(Locale.ROOT).matches(".*(password|secret|api.?key|authorization|credential|baseurl|proxy).*"),
                "Sensitive configuration is forbidden in Coding context");
            result.put(name, freezeValue(item, depth + 1));
        });
        return Collections.unmodifiableMap(result);
    }
    private static Object freezeValue(Object value, int depth) {
        CodingProtocol.require(depth < 12, "Coding context nesting limit");
        if (value instanceof Map<?, ?> map) return freeze(map, depth);
        if (value instanceof List<?> list) {
            CodingProtocol.require(list.size() <= 128, "Coding context list limit");
            return list.stream().map(item -> freezeValue(item, depth + 1)).toList();
        }
        if (value instanceof String text) {
            CodingProtocol.require(text.length() <= 16000, "Coding context text limit");
            return text;
        }
        CodingProtocol.require(value == null || value instanceof Boolean || value instanceof Number,
            "Unsupported Coding context value");
        return value;
    }
}
