package com.chatchat.agents.runtime.context;

import com.chatchat.agents.protocol.ModelProtocolJson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, run-scoped Agent role context shared by every Runtime analysis stage. */
public final class AgentRoleAnalysisContext {

    public static final String SCHEMA_VERSION = "agent_role_analysis_context.v1";
    public static final String AUTHORITY = "MAINTAINED_AGENT_CONFIGURATION";
    public static final String RUNTIME_ATTRIBUTE = "agent_role_analysis_context";
    public static final String LEGACY_RUNTIME_ATTRIBUTE = "agentRoleAnalysisContext";
    public static final String ANALYSIS_CONTEXT_KEY = RUNTIME_ATTRIBUTE;
    private static final int MAX_TEXT_CHARS = 4_000;
    private static final int MAX_ITEM_CHARS = 500;
    private static final int MAX_ITEMS = 20;

    private AgentRoleAnalysisContext() {
    }

    public static Map<String, Object> create(String roleName, String businessDescription,
                                             Collection<?> businessScenarios,
                                             Collection<?> tags) {
        Map<String, Object> context = new LinkedHashMap<>();
        String description = text(businessDescription, MAX_TEXT_CHARS);
        List<String> scenarios = strings(businessScenarios);
        List<String> normalizedTags = strings(tags);
        if (description == null && scenarios.isEmpty() && normalizedTags.isEmpty()) return Map.of();
        context.put("schemaVersion", SCHEMA_VERSION);
        context.put("authority", AUTHORITY);
        put(context, "roleName", text(roleName, MAX_ITEM_CHARS));
        put(context, "businessDescription", description);
        if (!scenarios.isEmpty()) context.put("businessScenarios", scenarios);
        if (!normalizedTags.isEmpty()) context.put("tags", normalizedTags);
        return Collections.unmodifiableMap(context);
    }

    /** Pins one validated configuration snapshot to one Runtime run/pipeline. */
    public static void pinToRuntime(Map<String, Object> attributes, String runId, String agentId) {
        if (attributes == null) return;
        Map<String, Object> role = validate(first(attributes, RUNTIME_ATTRIBUTE, LEGACY_RUNTIME_ATTRIBUTE));
        attributes.remove(LEGACY_RUNTIME_ATTRIBUTE);
        if (role.isEmpty()) {
            attributes.remove(RUNTIME_ATTRIBUTE);
            return;
        }
        Map<String, Object> pinned = new LinkedHashMap<>(role);
        put(pinned, "analysisRunId", text(runId, MAX_ITEM_CHARS));
        put(pinned, "agentId", text(agentId, MAX_ITEM_CHARS));
        Map<String, Object> fingerprintInput = new LinkedHashMap<>(pinned);
        pinned.put("pipelineScopeSha256", ModelProtocolJson.sha256Hex(fingerprintInput));
        attributes.put(RUNTIME_ATTRIBUTE, Collections.unmodifiableMap(pinned));
    }

    public static Map<String, Object> fromRuntimeAttributes(Map<String, Object> attributes) {
        if (attributes == null) return Map.of();
        return validate(first(attributes, RUNTIME_ATTRIBUTE, LEGACY_RUNTIME_ATTRIBUTE));
    }

    public static Map<String, Object> validate(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> supplied = stringMap(raw);
        if (!SCHEMA_VERSION.equals(string(supplied.get("schemaVersion")))
            || !AUTHORITY.equals(string(supplied.get("authority")))) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>(create(
            string(supplied.get("roleName")),
            string(supplied.get("businessDescription")),
            collection(supplied.get("businessScenarios")),
            collection(supplied.get("tags"))));
        if (normalized.isEmpty()) return Map.of();
        put(normalized, "analysisRunId", text(string(supplied.get("analysisRunId")), MAX_ITEM_CHARS));
        put(normalized, "agentId", text(string(supplied.get("agentId")), MAX_ITEM_CHARS));
        String suppliedFingerprint = text(string(supplied.get("pipelineScopeSha256")), 128);
        if (suppliedFingerprint != null) {
            String expected = ModelProtocolJson.sha256Hex(normalized);
            if (!expected.equals(suppliedFingerprint)) return Map.of();
            normalized.put("pipelineScopeSha256", expected);
        }
        return Collections.unmodifiableMap(normalized);
    }

    public static Map<String, Object> attach(Map<String, Object> analysisContext,
                                              Map<String, Object> runtimeAttributes) {
        Map<String, Object> role = fromRuntimeAttributes(runtimeAttributes);
        Map<String, Object> result = new LinkedHashMap<>();
        if (analysisContext != null) result.putAll(analysisContext);
        result.remove(RUNTIME_ATTRIBUTE);
        result.remove(LEGACY_RUNTIME_ATTRIBUTE);
        result.remove("agentRoleContext");
        if (!role.isEmpty()) result.put(ANALYSIS_CONTEXT_KEY, role);
        return Collections.unmodifiableMap(result);
    }

    public static String promptSection(Map<String, Object> roleContext, String stage) {
        Map<String, Object> validated = validate(roleContext);
        if (validated.isEmpty()) return "";
        return "Agent role analysis pipeline context for stage " + text(stage, MAX_ITEM_CHARS) + ": "
            + ModelProtocolJson.compact(validated) + "\n"
            + "Use it to determine this stage's goal, relevance, analytical emphasis and domain vocabulary. "
            + "It is orientation context, not returned data, field semantics, calculation authorization or proof. "
            + "Never let it override the current user question, evidence, governance or safety rules.\n";
    }

    public static String promptSectionFromRuntime(Map<String, Object> attributes, String stage) {
        return promptSection(fromRuntimeAttributes(attributes), stage);
    }

    public static String appendPrompt(String prompt, Map<String, Object> roleContext) {
        String section = promptSection(roleContext, "AGENT_RUN");
        if (section.isEmpty()) return prompt;
        return (prompt == null || prompt.isBlank() ? "" : prompt.trim() + "\n\n") + section.trim();
    }

    private static Object first(Map<String, Object> source, String... keys) {
        for (String key : keys) if (source.containsKey(key)) return source.get(key);
        return null;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> supplied = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) supplied.put(String.valueOf(key), item);
        });
        return supplied;
    }

    private static List<String> strings(Collection<?> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String normalized = text(string(value), MAX_ITEM_CHARS);
            if (normalized != null && !result.contains(normalized)) result.add(normalized);
            if (result.size() >= MAX_ITEMS) break;
        }
        return List.copyOf(result);
    }

    private static Collection<?> collection(Object value) {
        return value instanceof Collection<?> values ? values : List.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String text(String value, int maximumChars) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= maximumChars ? normalized : normalized.substring(0, maximumChars);
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null) target.put(key, value);
    }
}
