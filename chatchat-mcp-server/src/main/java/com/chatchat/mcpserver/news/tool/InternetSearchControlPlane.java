package com.chatchat.mcpserver.news.tool;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.news.runtime.NewsRuntimeClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
/** Admin-authenticated fixed MCP operations, delegated by the task's live Runtime governor. */
@RestController
@RequestMapping("/api/v1/internet-search")
public class InternetSearchControlPlane {
    private final NewsRuntimeClient news;private final InternetSearchMcpToolProvider tools;
    public InternetSearchControlPlane(NewsRuntimeClient news,InternetSearchMcpToolProvider tools){this.news=news;this.tools=tools;}
    @GetMapping("/providers") public ApiResponse<?> providers(){return ApiResponse.success(news.get("/internet-search/providers"));}
    @PostMapping("/providers") public ApiResponse<?> save(@RequestBody JsonNode request){return ApiResponse.success(news.post("/internet-search/providers",request));}
    @PostMapping("/read") public ApiResponse<?> read(@RequestBody JsonNode request){return ApiResponse.success(tools.read(request));}
}
