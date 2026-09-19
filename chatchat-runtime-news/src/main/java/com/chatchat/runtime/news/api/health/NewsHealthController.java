package com.chatchat.runtime.news.api.health;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.news.api.NewsApiPaths;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(value = NewsApiPaths.INTERNAL_V1, produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class NewsHealthController {
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of("service", "chatchat-runtime-news", "status", "UP"));
    }
}
