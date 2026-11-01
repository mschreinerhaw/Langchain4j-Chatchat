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
                card.remove("resources");
                card.put("builtin", true);
                card.putIfAbsent("enabled", true);
                card.put("removable", false);
                card.put("applicableAgents", "CLINE_CODING");
                return Map.copyOf(card);
            }).toList();
        } catch (java.io.IOException failure) { throw new IllegalStateException("Invalid builtin Coding Skill catalog", failure); }
    }
    public static List<Map<String,Object>> catalog() { return CATALOG; }
    /** Reserved image assets are never user-bindable business Skills. */
    public static boolean isBuiltinId(String id) { return id != null && id.trim().startsWith("builtin:"); }
    public static List<String> businessIds(Collection<String> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).map(String::trim)
            .filter(id -> !id.isBlank() && !isBuiltinId(id)).distinct().toList();
    }
}
