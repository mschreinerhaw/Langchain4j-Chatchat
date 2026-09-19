package com.chatchat.runtime.news.api.collection;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.news.api.NewsApiPaths;
import com.chatchat.runtime.news.application.collection.NewsCollectionOperations;
import com.chatchat.runtime.news.application.collection.NewsCollectionTasks;
import com.chatchat.runtime.news.compliance.RobotsComplianceReport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = NewsApiPaths.INTERNAL_V1 + "/sources/{sourceId}",
    produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class NewsCollectionController {
    private final NewsCollectionTasks tasks;
    private final NewsCollectionOperations collection;

    public NewsCollectionController(NewsCollectionTasks tasks, NewsCollectionOperations collection) {
        this.tasks = tasks;
        this.collection = collection;
    }

    @PostMapping("/collect")
    public ApiResponse<NewsCollectionTasks.Task> collect(@PathVariable("sourceId") Long sourceId) {
        return ApiResponse.success(tasks.submit(sourceId), "采集任务已提交");
    }

    @GetMapping("/collections/{executionId}")
    public ApiResponse<NewsCollectionTasks.Task> collectionStatus(
            @PathVariable("sourceId") Long sourceId,
            @PathVariable("executionId") String executionId) {
        return ApiResponse.success(tasks.get(sourceId, executionId));
    }

    @PostMapping("/robots-check")
    public ApiResponse<RobotsComplianceReport> checkRobots(@PathVariable("sourceId") Long sourceId) {
        return ApiResponse.success(collection.checkRobots(sourceId), "机器人协议检测完成");
    }
}
