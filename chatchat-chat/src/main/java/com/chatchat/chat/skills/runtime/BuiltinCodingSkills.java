package com.chatchat.chat.skills.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Metadata only; physical instructions are delivered exclusively by the Coding image. */
public final class BuiltinCodingSkills {
    private BuiltinCodingSkills() { }
    private static final List<Map<String,Object>> CATALOG = load();
    private static List<Map<String,Object>> load() {
        try (var input = BuiltinCodingSkills.class.getResourceAsStream("/coding-skills/catalog.json")) {
            if (input == null) throw new IllegalStateException("Builtin Coding Skill catalog missing");
            List<Map<String,Object>> values = new ObjectMapper().readValue(input, new TypeReference<>() { });
            return values.stream().map(value -> {
                var card = new LinkedHashMap<String,Object>(value);
                card.remove("references");
                card.put("builtin", true);
                card.put("removable", false);
                card.put("applicableAgents", "CLINE_CODING");
                return Map.copyOf(card);
            }).toList();
        } catch (java.io.IOException failure) { throw new IllegalStateException("Invalid builtin Coding Skill catalog", failure); }
    }
    public static List<Map<String,Object>> catalog() { return CATALOG; }
    public static List<String> disabled(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values) || values.stream().anyMatch(value ->
                !(value instanceof String) || CATALOG.stream().noneMatch(card -> value.equals(card.get("id")))))
            throw new IllegalArgumentException("Unknown builtin Coding Skill ID");
        return values.stream().map(String.class::cast).distinct().sorted().toList();
    }
}
