package com.chatchat.mcpserver.news;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class NewsSourcePresetSeeder {
    private static final Logger log = LoggerFactory.getLogger(NewsSourcePresetSeeder.class);
    private static final String LEGACY_SZSE_ENTRY_URL = "https://www.szse.cn/disclosure/listed/bulletin/index.html";
    private final NewsRuntimeClient runtime;
    private final NewsSourcePresetCatalog catalog;
    private volatile boolean initialSynchronizationCompleted;

    public NewsSourcePresetSeeder(NewsRuntimeClient runtime, NewsSourcePresetCatalog catalog) {
        this.runtime = runtime; this.catalog = catalog;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeWhenMcpIsReady() {
        seedMissingPresets();
    }

    @Scheduled(initialDelayString = "${chatchat.mcp.news-runtime.preset-retry-initial-delay-millis:10000}",
        fixedDelayString = "${chatchat.mcp.news-runtime.preset-retry-delay-millis:60000}")
    public void retryInitialSynchronization() {
        if (!initialSynchronizationCompleted) seedMissingPresets();
    }

    public synchronized boolean seedMissingPresets() {
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
            initialSynchronizationCompleted = true;
            return true;
        } catch (Exception ex) {
            // MCP must still start when the independently deployed News Runtime is temporarily unavailable.
            log.warn("News Runtime unavailable; preset synchronization deferred: {}", ex.getMessage());
            return false;
        }
    }

    private void migrate(JsonNode current, NewsSourcePresetCatalog.Preset preset) {
        boolean legacyCls = "cls_telegraph".equals(preset.code()) && "WEB_LIST".equals(current.path("sourceType").asText());
        boolean outdatedCls = "cls_telegraph".equals(preset.code())
            && current.path("configuration").path("presetVersion").asInt(0) < 2;
        boolean legacyCninfo = "cninfo_announcements".equals(preset.code())
            && "WEB_LIST".equals(current.path("sourceType").asText());
        boolean outdatedSseAnnouncements = "sse_announcements".equals(preset.code())
            && (!"SSE_ANNOUNCEMENTS".equals(current.path("sourceType").asText())
                || current.path("configuration").path("presetVersion").asInt(0) < 2);
        boolean legacySzse = "szse_announcements".equals(preset.code())
            && LEGACY_SZSE_ENTRY_URL.equals(current.path("entryUrl").asText());
        boolean outdatedSseHome = "sse_home".equals(preset.code())
            && current.path("configuration").path("presetVersion").asInt(0) < 2;
        boolean outdatedSzseHome = "szse_home".equals(preset.code())
            && (!"SZSE_HOME".equals(current.path("sourceType").asText())
                || current.path("configuration").path("presetVersion").asInt(0) < 2);
        boolean outdatedCninfoHome = "cninfo_home".equals(preset.code())
            && (!"CNINFO_HOME".equals(current.path("sourceType").asText())
                || current.path("configuration").path("presetVersion").asInt(0) < 2);
        boolean outdatedEastmoney = "eastmoney_finance".equals(preset.code())
            && current.path("configuration").path("presetVersion").asInt(0) < 2;
        if (!legacyCls && !outdatedCls && !legacyCninfo && !outdatedSseAnnouncements && !legacySzse
            && !outdatedSseHome && !outdatedSzseHome && !outdatedCninfoHome && !outdatedEastmoney) return;
        NewsSourcePresetCatalog.SourceUpsert source = preset.source();
        var request = new NewsSourcePresetCatalog.SourceUpsert(source.sourceCode(), source.sourceName(),
            legacyCls || outdatedCls || legacyCninfo || outdatedSseAnnouncements || outdatedSzseHome || outdatedCninfoHome
                ? source.sourceType() : current.path("sourceType").asText(),
            legacySzse || outdatedSseAnnouncements || outdatedSzseHome || outdatedCninfoHome
                ? source.entryUrl() : current.path("entryUrl").asText(), source.allowedDomain(),
            source.scheduleCron(), current.path("enabled").asBoolean(false),
            legacyCls || outdatedCls || legacyCninfo || outdatedSseAnnouncements || outdatedSseHome || outdatedSzseHome
                || outdatedCninfoHome || outdatedEastmoney
                ? source.configuration() : jsonMap(current.path("configuration")));
        if (outdatedEastmoney && preset.rule() != null) {
            runtime.put("/sources/" + current.path("id").asLong() + "/rule", preset.rule());
        }
        runtime.put("/sources/" + current.path("id").asLong(), request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(JsonNode node) {
        return node.isObject() ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Map.class) : Map.of();
    }
}
