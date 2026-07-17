package com.chatchat.mcpserver.news;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class NewsSourcePresetSeeder {
    private static final Logger log = LoggerFactory.getLogger(NewsSourcePresetSeeder.class);
    private static final String LEGACY_SZSE_ENTRY_URL = "https://www.szse.cn/disclosure/listed/bulletin/index.html";
    private final NewsRuntimeClient runtime;
    private final NewsSourcePresetCatalog catalog;

    public NewsSourcePresetSeeder(NewsRuntimeClient runtime, NewsSourcePresetCatalog catalog) {
        this.runtime = runtime; this.catalog = catalog;
    }

    @PostConstruct
    public void seedMissingPresets() {
        try {
            Map<String, JsonNode> existing = new HashMap<>();
            runtime.get("/sources").forEach(source -> existing.put(source.path("sourceCode").asText(), source));
            for (var preset : catalog.presets()) {
                JsonNode current = existing.get(preset.code());
                if (current == null) {
                    JsonNode created = runtime.post("/sources", preset.source());
                    if (preset.rule() != null) runtime.put("/sources/" + created.path("id").asLong() + "/rule", preset.rule());
                } else {
                    migrate(current, preset);
                }
            }
        } catch (Exception ex) {
            // MCP must still start when the independently deployed News Runtime is temporarily unavailable.
            log.warn("News Runtime unavailable; preset synchronization deferred: {}", ex.getMessage());
        }
    }

    private void migrate(JsonNode current, NewsSourcePresetCatalog.Preset preset) {
        boolean legacyCls = "cls_telegraph".equals(preset.code()) && "WEB_LIST".equals(current.path("sourceType").asText());
        boolean legacySzse = "szse_announcements".equals(preset.code())
            && LEGACY_SZSE_ENTRY_URL.equals(current.path("entryUrl").asText());
        if (!legacyCls && !legacySzse) return;
        NewsSourcePresetCatalog.SourceUpsert source = preset.source();
        var request = new NewsSourcePresetCatalog.SourceUpsert(source.sourceCode(), source.sourceName(),
            legacyCls ? source.sourceType() : current.path("sourceType").asText(),
            legacySzse ? source.entryUrl() : current.path("entryUrl").asText(), source.allowedDomain(),
            source.scheduleCron(), current.path("enabled").asBoolean(false),
            legacyCls ? source.configuration() : jsonMap(current.path("configuration")));
        runtime.put("/sources/" + current.path("id").asLong(), request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(JsonNode node) {
        return node.isObject() ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Map.class) : Map.of();
    }
}
