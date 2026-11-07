package com.chatchat.common.runtime.coding;

import java.util.*;

/** Public, secret-free configuration snapshot. It grants no permissions beyond Authorization. */
public final class CodingRuntimeContext {
    private CodingRuntimeContext() { }
    public static Map<String, Object> validate(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        CodingProtocol.require(value.keySet().stream().allMatch(Set.of("schemaVersion", "capturedAt",
            "consistency", "model", "skills", "builtinSkillPolicy", "configuredSkillIds", "inputFiles", "executionProfile", "reportMode", "reportPresentation", "pythonEnvironment", "scriptPurpose", "codingLifecycle", "analysisResultMode", "analysisInstructions", "codeProject", "knowledgeExploration", "internetSearch", "internetAvailability", "nativeExecutionPolicy", "nativeExecutorCapability", "nativeMcp")::contains), "Unknown Coding context field");
        if(value.containsKey("nativeMcp")){
            CodingProtocol.require(value.get("nativeMcp") instanceof List<?> cards&&cards.size()<=64,"Invalid native MCP catalog");
            var names=new HashSet<String>();var remoteNames=new HashSet<String>();
            for(Object item:(List<?>)value.get("nativeMcp")){
                CodingProtocol.require(item instanceof Map<?,?>,"Invalid native MCP descriptor");var card=(Map<?,?>)item;
                CodingProtocol.require(card.keySet().equals(Set.of("serverName","name","agentToolName","agentId","description","inputSchema","schemaSha256")),"Invalid native MCP descriptor fields");
                for(String field:List.of("serverName","name","agentId"))CodingProtocol.require(card.get(field) instanceof String s&&!s.isBlank()&&s.length()<=256,"Invalid native MCP identity");
                CodingProtocol.require(card.get("agentToolName") instanceof String name&&name.matches("[a-zA-Z0-9_-]{1,64}")&&!NativeCodeWorkspace.TOOLS.contains(name)&&names.add(name),"Duplicate or invalid native MCP tool");
                CodingProtocol.require(remoteNames.add(card.get("serverName")+"\u0000"+card.get("name")),"Ambiguous native MCP remote identity");
                CodingProtocol.require(card.get("description") instanceof String description&&description.length()<=16384&&card.get("inputSchema") instanceof Map<?,?> schema&&!schema.isEmpty()
                    &&CodingCanonicalHash.sha256Value(card.get("inputSchema")).equals(card.get("schemaSha256")),"Invalid frozen native MCP Schema");
            }
        }
        if(value.containsKey("nativeExecutionPolicy")||value.containsKey("nativeExecutorCapability"))
            CodingProtocol.require(NativeExecutorPolicy.PIPEFAIL.id().equals(value.get("nativeExecutionPolicy"))
                &&value.get("nativeExecutorCapability") instanceof String capability
                &&capability.matches("NATIVE_EXECUTOR:BASH_PIPEFAIL_V1:[a-f0-9]{64}:[a-f0-9]{64}"),"Invalid native executor policy snapshot");
        if(value.containsKey("internetAvailability")){
            CodingProtocol.require(value.get("internetAvailability") instanceof Map<?,?>,"Internet availability status required");
            var p=(Map<?,?>)value.get("internetAvailability");
            CodingProtocol.require(p.keySet().equals(Set.of("schemaVersion","status","reasonCode","serviceChecked","toolsAvailable"))
                &&"runtime_os.internet.availability.v1".equals(p.get("schemaVersion"))
                &&p.get("status") instanceof String status&&Set.of("AVAILABLE","UNAVAILABLE","UNKNOWN").contains(status)
                &&p.get("reasonCode") instanceof String code&&code.matches("[A-Z_]{1,64}")
                &&Boolean.FALSE.equals(p.get("serviceChecked"))&&p.get("toolsAvailable") instanceof Boolean,
                "Invalid Internet availability status");
            boolean available="AVAILABLE".equals(p.get("status"));
            CodingProtocol.require(available==Boolean.TRUE.equals(p.get("toolsAvailable"))&&(!available||value.containsKey("internetSearch")),
                "Availability cannot grant an Internet capability");
        }
        if(value.containsKey("internetSearch")){
            CodingProtocol.require(value.get("internetSearch") instanceof Map<?,?>,"Internet capability required");var p=(Map<?,?>)value.get("internetSearch");
            var required=Set.of("schemaVersion","modelName","modelRevision","providerId","providerRevision","agentId","explicitlyRequested");
            var allowed=new HashSet<>(required);allowed.add("providers");
            CodingProtocol.require(p.keySet().containsAll(required)&&allowed.containsAll(p.keySet())
                &&"runtime_os.internet.v1".equals(p.get("schemaVersion"))&&p.get("modelRevision") instanceof Number m&&m.longValue()>0
                &&p.get("providerRevision") instanceof Number r&&r.longValue()>0&&p.get("explicitlyRequested") instanceof Boolean,"Invalid Internet capability");
            for(String k:List.of("modelName","agentId","providerId"))CodingProtocol.require(p.get(k) instanceof String s&&!s.isBlank()&&s.length()<=256,"Invalid Internet identity");
            if(p.containsKey("providers")){
                CodingProtocol.require(p.get("providers") instanceof List<?> list&&!list.isEmpty()&&list.size()<=16,"Invalid Internet providers");
                var ids=new HashSet<String>();var list=(List<?>)p.get("providers");
                for(Object item:list){
                    CodingProtocol.require(item instanceof Map<?,?>,"Invalid Internet provider");var provider=(Map<?,?>)item;
                    CodingProtocol.require(provider.keySet().containsAll(Set.of("id","name","revision"))
                        &&Set.of("id","name","revision","supportsNews","supportsLanguageFilter").containsAll(provider.keySet())
                        &&provider.get("id") instanceof String id&&id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")&&ids.add(id)
                        &&provider.get("name") instanceof String name&&!name.isBlank()&&name.length()<=128
                        &&provider.get("revision") instanceof Number revision&&revision.longValue()>0,"Invalid Internet provider binding");
                    for(String flag:List.of("supportsNews","supportsLanguageFilter"))if(provider.containsKey(flag))
                        CodingProtocol.require(provider.get(flag) instanceof Boolean,"Invalid Internet provider feature");
                }
                var first=(Map<?,?>)list.get(0);
                CodingProtocol.require(Objects.equals(first.get("id"),p.get("providerId"))&&((Number)first.get("revision")).longValue()==((Number)p.get("providerRevision")).longValue(),"Invalid primary Internet provider");
            }
        }
        if(value.containsKey("knowledgeExploration")) {
            CodingProtocol.require(value.get("knowledgeExploration") instanceof Map<?,?>, "Knowledge profile required");
            var profile=(Map<?,?>)value.get("knowledgeExploration");
            CodingProtocol.require(profile.keySet().containsAll(Set.of("schemaVersion","agentId"))
                && Set.of("schemaVersion","agentId","resourceIds","explicitlyRequested").containsAll(profile.keySet())
                && "runtime_os.knowledge.v1".equals(profile.get("schemaVersion"))
                && profile.get("agentId") instanceof String id && !id.isBlank() && id.length()<=256,
                "Invalid knowledge exploration profile");
            if(profile.containsKey("explicitlyRequested"))CodingProtocol.require(profile.get("explicitlyRequested") instanceof Boolean,"Invalid explicit resource choice");
            if(profile.containsKey("resourceIds"))CodingProtocol.require(profile.get("resourceIds") instanceof List<?> ids&&ids.size()<=4
                &&new HashSet<>(ids).size()==ids.size()&&ids.stream().allMatch(id->id instanceof String&&com.chatchat.common.retrieval.AgentResourcePolicy.IDS.contains(id)),"Invalid logical resource set");
        }
        if(value.containsKey("analysisInstructions"))CodingProtocol.require(
            value.get("analysisInstructions") instanceof List<?> rules && !rules.isEmpty() && rules.size()<=16
                && rules.stream().allMatch(rule->rule instanceof String text && text.length()<=12000),
            "Invalid Runtime analysis instructions");
        if(value.containsKey("codeProject"))PythonCodeProject.fromContext(value.get("codeProject"));
        if(value.containsKey("analysisResultMode"))CodingProtocol.require("TEXT_OR_CODE".equals(value.get("analysisResultMode")),"Invalid analysis result mode");
        if(value.containsKey("scriptPurpose"))CodingProtocol.require(value.get("scriptPurpose") instanceof String purpose
            &&!purpose.isBlank()&&purpose.length()<=512,"Invalid script business purpose");
        if (value.containsKey("pythonEnvironment")) {
            CodingProtocol.require(value.get("pythonEnvironment") instanceof Map<?,?>, "Python environment snapshot required");
            var env = (Map<?,?>) value.get("pythonEnvironment");
            CodingProtocol.require(env.keySet().stream().allMatch(Set.of("environmentId", "environmentVersion", "dockerImage",
                "imageDigest", "pythonVersion", "packages", "source", "assetId")::contains), "Unknown Python environment field");
            CodingProtocol.require(env.get("environmentId") instanceof String id && !id.isBlank(), "Python environment identity required");
            CodingProtocol.require(env.get("environmentVersion") instanceof Number n && n.longValue() > 0, "Python environment version required");
            if (env.containsKey("imageDigest")) CodingProtocol.require(env.get("imageDigest") instanceof String digest
                && digest.matches("sha256:[0-9a-f]{64}"), "Invalid Python image digest");
        }
        if(value.containsKey("codingLifecycle")) {
            CodingProtocol.require(value.get("codingLifecycle") instanceof Map<?,?>,"Code lifecycle policy required");
            var policy=(Map<?,?>)value.get("codingLifecycle");
            CodingProtocol.require(policy.keySet().equals(Set.of("schemaVersion","patchOnlyAfterFirst"))
                && "runtime_os.code_lifecycle.v1".equals(policy.get("schemaVersion"))
                && Boolean.TRUE.equals(policy.get("patchOnlyAfterFirst")),"Unsupported code lifecycle policy");
        }
        VisualReportMode.read(value);
        ReportPresentation.read(value);
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
        return freeze(value, 0, "");
    }
    private static Map<String, Object> freeze(Map<?, ?> value, int depth, String path) {
        CodingProtocol.require(depth < 12 && value.size() <= 128, "Coding context is too large");
        Map<String, Object> result = new TreeMap<>();
        value.forEach((key, item) -> {
            CodingProtocol.require(key instanceof String, "Coding context keys must be strings");
            String name = (String) key;
            CodingProtocol.require(!name.toLowerCase(Locale.ROOT).matches(".*(password|secret|api.?key|authorization|credential|baseurl|proxy).*"),
                "Sensitive configuration is forbidden in Coding context");
            result.put(name, freezeValue(item, depth + 1, path+"/"+name.replace("~","~0").replace("/","~1")));
        });
        return Collections.unmodifiableMap(result);
    }
    private static Object freezeValue(Object value, int depth, String path) {
        CodingProtocol.require(depth < 12, "Coding context nesting limit");
        if (value instanceof Map<?, ?> map) return freeze(map, depth, path);
        if (value instanceof List<?> list) {
            if (path.equals("/configuredSkillIds"))
                CodingProtocol.require(list.stream().allMatch(String.class::isInstance), "Invalid configured Skill identities");
            else if (path.equals("/skills/skills"))
                CodingProtocol.require(list.stream().allMatch(Map.class::isInstance), "Invalid Skill capability catalog");
            else CodingProtocol.require(list.size() <= 128, "Coding context list limit");
            // Skill metadata is bounded by the unchanged transport/model budgets,
            // not an arbitrary package count. All nested card safeguards remain.
            return list.stream().map(item -> freezeValue(item, depth + 1, path+"[]")).toList();
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
