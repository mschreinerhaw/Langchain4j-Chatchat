package com.chatchat.mcpserver.search;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/mcp-search-index")
public class McpSearchIndexAdminController {

    private final McpAssetLuceneIndexService assetLuceneIndexService;
    private final McpTemplateLuceneIndexService templateLuceneIndexService;

    @PostMapping("/assets/rebuild")
    public ApiResponse<Map<String, Object>> rebuildAssetIndex() {
        return ApiResponse.success(assetLuceneIndexService.refreshAll(), "MCP asset Lucene index rebuilt");
    }

    @PostMapping("/templates/rebuild")
    public ApiResponse<Map<String, Object>> rebuildTemplateIndex() {
        templateLuceneIndexService.refreshAll();
        return ApiResponse.success(Map.of("refreshed", true), "MCP template Lucene index rebuilt");
    }
}
