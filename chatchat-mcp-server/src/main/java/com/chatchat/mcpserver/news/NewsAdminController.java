package com.chatchat.mcpserver.news;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/news")
public class NewsAdminController {
    private final NewsRuntimeClient runtime;
    private final NewsSourcePresetCatalog presetCatalog;
    private final NewsExtractionPatternCatalog patternCatalog;
    private final NewsCollectionTemplateCatalog collectionTemplateCatalog;
    private final NewsSourcePresetSeeder presetSeeder;

    public NewsAdminController(NewsRuntimeClient runtime, NewsSourcePresetCatalog presetCatalog,
                               NewsExtractionPatternCatalog patternCatalog,
                               NewsCollectionTemplateCatalog collectionTemplateCatalog,
                               NewsSourcePresetSeeder presetSeeder) {
        this.runtime = runtime;
        this.presetCatalog = presetCatalog;
        this.patternCatalog = patternCatalog;
        this.collectionTemplateCatalog = collectionTemplateCatalog;
        this.presetSeeder = presetSeeder;
    }

    @GetMapping("/sources")
    public ApiResponse<JsonNode> list() {
        presetSeeder.seedMissingPresets();
        return ApiResponse.success(runtime.get("/sources"));
    }
    @PostMapping("/sources")
    public ApiResponse<JsonNode> create(@RequestBody JsonNode request) {
        return ApiResponse.success(runtime.post("/sources", request));
    }
    @PutMapping("/sources/{id}")
    public ApiResponse<JsonNode> update(@PathVariable("id") Long id, @RequestBody JsonNode request) {
        return ApiResponse.success(runtime.put("/sources/" + id, request));
    }
    @DeleteMapping("/sources/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) { runtime.delete("/sources/" + id); return ApiResponse.success(null); }
    @GetMapping("/sources/{id}/rule")
    public ApiResponse<JsonNode> rule(@PathVariable("id") Long id) { return ApiResponse.success(runtime.get("/sources/" + id + "/rule")); }
    @PutMapping("/sources/{id}/rule")
    public ApiResponse<JsonNode> saveRule(@PathVariable("id") Long id, @RequestBody JsonNode request) {
        return ApiResponse.success(runtime.put("/sources/" + id + "/rule", request));
    }
    @PostMapping("/sources/{id}/collect")
    public ApiResponse<JsonNode> collect(@PathVariable("id") Long id) {
        return ApiResponse.success(runtime.post("/sources/" + id + "/collect", null), "采集完成");
    }
    @PostMapping("/sources/{id}/robots-check")
    public ApiResponse<JsonNode> checkRobots(@PathVariable("id") Long id) {
        return ApiResponse.success(runtime.post("/sources/" + id + "/robots-check", null), "机器人协议检测完成");
    }
    @GetMapping("/records")
    public ApiResponse<JsonNode> records(@RequestParam(value = "sourceId", required = false) Long sourceId,
                                         @RequestParam(value = "page", defaultValue = "0") int page,
                                         @RequestParam(value = "size", defaultValue = "20") int size) {
        String path = "/records?page=" + Math.max(0, page) + "&size=" + Math.max(1, Math.min(size, 100));
        if (sourceId != null) path += "&sourceId=" + sourceId;
        return ApiResponse.success(runtime.get(path));
    }
    @PostMapping("/search")
    public ApiResponse<ToolOutput> search(@RequestBody(required = false) java.util.Map<String, Object> request) {
        java.util.Map<String, Object> parameters = request == null ? java.util.Map.of() : new java.util.LinkedHashMap<>(request);
        return ApiResponse.success(runtime.invoke("news_search", ToolInput.builder().parameters(parameters).build()));
    }
    @GetMapping("/presets")
    public ApiResponse<List<NewsSourcePresetCatalog.Preset>> presets() { return ApiResponse.success(presetCatalog.presets()); }
    @GetMapping("/pattern-presets")
    public ApiResponse<List<NewsExtractionPatternCatalog.PatternPreset>> patternPresets() {
        return ApiResponse.success(patternCatalog.presets());
    }
    @GetMapping("/collection-templates")
    public ApiResponse<List<NewsCollectionTemplateCatalog.CollectionTemplate>> collectionTemplates() {
        return ApiResponse.success(collectionTemplateCatalog.templates());
    }

}
