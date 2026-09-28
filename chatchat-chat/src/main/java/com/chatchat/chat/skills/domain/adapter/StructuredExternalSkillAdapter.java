package com.chatchat.chat.skills.domain.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;

/** JSON/YAML declarative Agent exports; SDK code and arbitrary tool definitions are never executed. */
@Component
@Order(-100)
public final class StructuredExternalSkillAdapter implements ExternalSkillAdapter {
    private final ObjectMapper mapper;
    public StructuredExternalSkillAdapter(ObjectMapper mapper) { this.mapper = mapper; }
    public boolean supports(ExternalSkillSource source) {
        return source != null && source.fileName() != null && source.fileName().toLowerCase(Locale.ROOT).matches(".*\\.(json|yaml|yml)$");
    }
    public AdaptedExternalSkill adapt(ExternalSkillSource source) {
        if (!supports(source) || source.content().length() > 512 * 1024) throw new IllegalArgumentException("Invalid structured Skill source");
        try {
            Object loaded;
            if (source.fileName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                loaded = mapper.readerFor(new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(source.content());
            } else {
                var options = new LoaderOptions();
                options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
                options.setNestingDepthLimit(12); options.setCodePointLimit(512 * 1024);
                loaded = new Yaml(new SafeConstructor(options)).load(source.content());
            }
            if (!(loaded instanceof Map<?, ?> root)) throw new IllegalArgumentException("Agent export must be an object");
            Map<String, Object> metadata = new LinkedHashMap<>();
            root.forEach((key, value) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("Agent export keys must be strings");
                metadata.put(name, value);
            });
            String name = text(metadata, "name", "displayName");
            String instructions = text(metadata, "instructions", "instruction", "systemPrompt", "system_prompt");
            if (name.isBlank() || instructions.isBlank()) throw new IllegalArgumentException("Agent export requires name and instructions");
            return new AdaptedExternalSkill(name, text(metadata, "description"), instructions, "DECLARATIVE_AGENT",
                Map.of("frontMatter", metadata, "sourceReference", source.sourceReference() == null ? "" : source.sourceReference()));
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("Malformed Agent JSON", failure); }
    }
    private String text(Map<String, Object> values, String... keys) {
        for (String key : keys) if (values.get(key) instanceof String value && !value.isBlank()) return value.trim();
        return "";
    }
}
