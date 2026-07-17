package com.chatchat.mcpserver.news;

import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/news")
public class NewsAdminController {
    private final NewsRuntimeClient runtime;
    private final NewsSourcePresetCatalog presetCatalog;
    private final NewsExtractionPatternCatalog patternCatalog;

    public NewsAdminController(NewsRuntimeClient runtime, NewsSourcePresetCatalog presetCatalog,
                               NewsExtractionPatternCatalog patternCatalog) {
        this.runtime = runtime;
        this.presetCatalog = presetCatalog;
        this.patternCatalog = patternCatalog;
    }

    @GetMapping("/sources")
    public ApiResponse<JsonNode> list() { return ApiResponse.success(runtime.get("/sources")); }
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
    @GetMapping("/presets")
    public ApiResponse<List<NewsSourcePresetCatalog.Preset>> presets() { return ApiResponse.success(presetCatalog.presets()); }
    @GetMapping("/pattern-presets")
    public ApiResponse<List<NewsExtractionPatternCatalog.PatternPreset>> patternPresets() {
        return ApiResponse.success(patternCatalog.presets());
    }

}
