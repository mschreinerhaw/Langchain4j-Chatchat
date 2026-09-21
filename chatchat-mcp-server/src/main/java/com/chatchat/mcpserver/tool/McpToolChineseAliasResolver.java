package com.chatchat.mcpserver.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/** Resolves display aliases from explicit MCP metadata or the editable alias table. */
@Component
public class McpToolChineseAliasResolver {

    private static volatile McpToolChineseAliasResolver instance;

    private final McpToolAliasRepository repository;
    private final ObjectMapper objectMapper;

    public McpToolChineseAliasResolver(McpToolAliasRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        instance = this;
    }

    @PostConstruct
    public void seedDefaults() throws IOException {
        try (var input = new ClassPathResource("mcp-tool-alias-seed.json").getInputStream()) {
            Map<String, String> defaults = objectMapper.readValue(input, new TypeReference<>() {});
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                if (!repository.existsById(entry.getKey())) {
                    repository.save(new McpToolAlias(entry.getKey(), entry.getValue()));
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        if (instance == this) instance = null;
    }

    public static String resolve(String name, String title, Map<String, Object> metadata) {
        if (metadata != null) {
            String explicit = chineseText(metadata.get("chineseAlias"));
            if (explicit != null) return explicit;
        }
        McpToolChineseAliasResolver current = instance;
        if (current != null) {
            String byName = current.find(name == null ? null : "name:" + name);
            if (byName != null) return byName;
            String byTitle = current.find(title == null ? null : "title:" + title);
            if (byTitle != null) return byTitle;
        }
        String metadataTitle = metadata == null ? null : chineseText(metadata.get("title"));
        return metadataTitle != null ? metadataTitle : chineseText(title);
    }

    private String find(String key) {
        return key == null ? null : repository.findById(key)
            .map(McpToolAlias::getChineseAlias).map(McpToolChineseAliasResolver::chineseText)
            .orElse(null);
    }

    private static String chineseText(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && text.length() <= 128 && text.codePoints().anyMatch(code ->
            Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN) ? text : null;
    }
}
