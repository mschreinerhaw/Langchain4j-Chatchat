package com.chatchat.runtime.news;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.collector.NewsCollectionService;
import com.chatchat.runtime.news.source.NewsSourceAdminService;
import com.chatchat.runtime.news.tool.NewsMcpToolProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/news")
public class NewsRuntimeInternalController {
    private static final long NEWS_CAPABILITY_ID = 1L;
    private final NewsSourceAdminService sources;
    private final NewsCollectionService collection;
    private final NewsMcpToolProvider tools;

    public NewsRuntimeInternalController(NewsSourceAdminService sources, NewsCollectionService collection,
                                         NewsMcpToolProvider tools) {
        this.sources = sources; this.collection = collection; this.tools = tools;
    }

    @GetMapping("/health") public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of("service", "chatchat-runtime-news", "status", "UP"));
    }
    @GetMapping("/sources") public ApiResponse<List<NewsSourceAdminService.NewsSourceView>> list() {
        return ApiResponse.success(sources.list(NEWS_CAPABILITY_ID));
    }
    @PostMapping("/sources") public ApiResponse<NewsSourceAdminService.NewsSourceView> create(
        @RequestBody NewsSourceAdminService.NewsSourceUpsert request) {
        return ApiResponse.success(sources.create(NEWS_CAPABILITY_ID, request));
    }
    @PutMapping("/sources/{id}") public ApiResponse<NewsSourceAdminService.NewsSourceView> update(
        @PathVariable("id") Long id, @RequestBody NewsSourceAdminService.NewsSourceUpsert request) {
        return ApiResponse.success(sources.update(NEWS_CAPABILITY_ID, id, request));
    }
    @DeleteMapping("/sources/{id}") public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        sources.delete(NEWS_CAPABILITY_ID, id); return ApiResponse.success(null);
    }
    @GetMapping("/sources/{id}/rule") public ApiResponse<NewsSourceAdminService.NewsRuleView> rule(@PathVariable("id") Long id) {
        return ApiResponse.success(sources.getRule(NEWS_CAPABILITY_ID, id));
    }
    @PutMapping("/sources/{id}/rule") public ApiResponse<NewsSourceAdminService.NewsRuleView> saveRule(
        @PathVariable("id") Long id, @RequestBody NewsSourceAdminService.NewsRuleUpsert request) {
        return ApiResponse.success(sources.saveRule(NEWS_CAPABILITY_ID, id, request));
    }
    @PostMapping("/sources/{id}/collect") public ApiResponse<?> collect(@PathVariable("id") Long id) {
        return ApiResponse.success(collection.collect(id, "mcp-admin-" + UUID.randomUUID()), "采集完成");
    }
    @PostMapping("/sources/{id}/robots-check") public ApiResponse<?> checkRobots(@PathVariable("id") Long id) {
        return ApiResponse.success(collection.checkRobots(id), "机器人协议检测完成");
    }
    @GetMapping("/records") public ApiResponse<NewsSourceAdminService.NewsRecordPage> records(
        @RequestParam(value = "sourceId", required = false) Long sourceId,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.success(sources.records(sourceId, page, size));
    }
    @PostMapping("/tools/{toolName}") public ApiResponse<ToolOutput> invoke(
        @PathVariable("toolName") String toolName, @RequestBody ToolInput input) {
        return ApiResponse.success(tools.findExecutor(toolName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown news tool: " + toolName)).execute(input));
    }
}
