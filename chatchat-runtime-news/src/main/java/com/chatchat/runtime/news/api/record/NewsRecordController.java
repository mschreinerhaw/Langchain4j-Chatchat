package com.chatchat.runtime.news.api.record;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.news.api.NewsApiPaths;
import com.chatchat.runtime.news.application.source.NewsSourceAdministration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = NewsApiPaths.INTERNAL_V1 + "/records",
    produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class NewsRecordController {
    private final NewsSourceAdministration sources;

    public NewsRecordController(NewsSourceAdministration sources) {
        this.sources = sources;
    }

    @GetMapping
    public ApiResponse<NewsSourceAdministration.NewsRecordPage> records(
            @RequestParam(value = "sourceId", required = false) Long sourceId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.success(sources.records(sourceId, page, size));
    }
}
