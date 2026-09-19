package com.chatchat.runtime.news.api.tool;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.api.NewsApiPaths;
import com.chatchat.runtime.news.application.tool.NewsToolRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = NewsApiPaths.INTERNAL_V1 + "/tools",
    produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class NewsToolController {
    private final NewsToolRegistry tools;

    public NewsToolController(NewsToolRegistry tools) {
        this.tools = tools;
    }

    @PostMapping("/{toolName}")
    public ApiResponse<ToolOutput> invoke(@PathVariable("toolName") String toolName,
                                          @RequestBody ToolInput input) {
        ToolOutput output = tools.findExecutor(toolName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown news tool: " + toolName))
            .execute(input);
        return ApiResponse.success(output);
    }
}
