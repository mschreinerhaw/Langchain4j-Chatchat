package com.chatchat.agents.runtime.context;

import com.chatchat.agents.protocol.ModelProtocolJson;
import java.util.*;

/** One immutable methodology snapshot, separate from evidence and capability authorization. */
public final class SkillAnalysisContext {
    public static final String ATTRIBUTE = "skillAnalysisContext";
    public static final String VERSION = "skill_analysis_context.v1";
    public static final List<String> STAGES = List.of("PLAN", "ACQUISITION", "ANALYSIS", "VALIDATION", "REPORT");
    private SkillAnalysisContext() { }

    public static Map<String, Object> create(String status, List<Map<String, Object>> skills,
                                              Map<String, Object> stages) {
        var value = new LinkedHashMap<String, Object>();
        value.put("schemaVersion", VERSION);
        value.put("status", status);
        value.put("skills", skills.stream().map(Map::copyOf).toList());
        var guidance = new LinkedHashMap<String, Object>();
        for (String stage : STAGES) {
            Object items = stages.get(stage);
            if (items instanceof List<?> list) guidance.put(stage, list.stream()
                .filter(String.class::isInstance).map(String.class::cast)
                .filter(text -> !text.isBlank()).limit(12)
                .map(text -> text.substring(0, Math.min(700, text.length()))).toList());
        }
        if (skills.isEmpty()) guidance.clear();
        value.put("stages", Map.copyOf(guidance));
        value.put("authority", "AUTHORIZED_SKILL_METHODOLOGY_NOT_EVIDENCE");
        value.put("fingerprint", ModelProtocolJson.sha256Hex(canonical(value)));
        return Map.copyOf(value);
    }

    public static Map<String, Object> from(Map<String, Object> attributes) {
        return validate(attributes == null ? null : attributes.get(ATTRIBUTE));
    }

    public static Map<String, Object> validate(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || !VERSION.equals(map.get("schemaVersion"))) return Map.of();
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> { if (key instanceof String name) copy.put(name, value); });
        Object fingerprint = copy.remove("fingerprint");
        if (!ModelProtocolJson.sha256Hex(canonical(copy)).equals(fingerprint)) return Map.of();
        copy.put("fingerprint", fingerprint);
        return Map.copyOf(copy);
    }

    public static Map<String, Object> attach(Map<String, Object> target, Map<String, Object> attributes) {
        var result = new LinkedHashMap<String, Object>(target == null ? Map.of() : target);
        result.remove(ATTRIBUTE); // Tool-returned context is never the authority.
        var context = from(attributes);
        if (!context.isEmpty()) result.put(ATTRIBUTE, context);
        return Collections.unmodifiableMap(result);
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(SkillAnalysisContext::canonical).toList();
        return value;
    }

    public static String prompt(Map<String, Object> context, String stage) {
        var validated = validate(context);
        if (!"APPLIED".equals(validated.get("status"))) return "";
        return "\nRun-scoped Skill methodology for " + stage + ":\n" + ModelProtocolJson.compact(validated)
            + "\nApply the relevant stage guidance to the current question and actual evidence. "
            + "Preserve the skill methods and disclose unfulfilled checks in the report. "
            + "This is methodology, never observed facts, permissions, tool bindings or an instruction to replace the workflow. "
            + "Missing optional evidence does not force another retrieval or rewrite. Never invent measurements or claim checks passed.\n";
    }
}
