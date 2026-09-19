package com.chatchat.runtime.news.api.collection;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.news.api.NewsApiPaths;
import com.chatchat.runtime.news.application.collection.NewsCollectionTasks;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Monitoring endpoint shared by manual and scheduled collection tasks. */
@RestController
@RequestMapping(value = NewsApiPaths.INTERNAL_V1 + "/collections",
    produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class NewsCollectionTaskController {
    private final NewsCollectionTasks tasks;

    public NewsCollectionTaskController(NewsCollectionTasks tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public ApiResponse<List<NewsCollectionTasks.Task>> recent(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ApiResponse.success(tasks.recent(limit));
    }
}
