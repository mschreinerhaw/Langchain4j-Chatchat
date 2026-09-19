package com.chatchat.runtime.news.api.source;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.runtime.news.api.NewsApiPaths;
import com.chatchat.runtime.news.application.source.NewsSourceAdministration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = NewsApiPaths.INTERNAL_V1 + "/sources",
    produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class NewsSourceController {
    private static final long NEWS_CAPABILITY_ID = 1L;
    private final NewsSourceAdministration sources;

    public NewsSourceController(NewsSourceAdministration sources) {
        this.sources = sources;
    }

    @GetMapping
    public ApiResponse<List<NewsSourceAdministration.NewsSourceView>> list() {
        return ApiResponse.success(sources.list(NEWS_CAPABILITY_ID));
    }

    @PostMapping
    public ApiResponse<NewsSourceAdministration.NewsSourceView> create(
            @RequestBody NewsSourceAdministration.NewsSourceUpsert request) {
        return ApiResponse.success(sources.create(NEWS_CAPABILITY_ID, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<NewsSourceAdministration.NewsSourceView> update(
            @PathVariable("id") Long id,
            @RequestBody NewsSourceAdministration.NewsSourceUpsert request) {
        return ApiResponse.success(sources.update(NEWS_CAPABILITY_ID, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        sources.delete(NEWS_CAPABILITY_ID, id);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/rule")
    public ApiResponse<NewsSourceAdministration.NewsRuleView> rule(@PathVariable("id") Long id) {
        return ApiResponse.success(sources.getRule(NEWS_CAPABILITY_ID, id));
    }

    @PutMapping("/{id}/rule")
    public ApiResponse<NewsSourceAdministration.NewsRuleView> saveRule(
            @PathVariable("id") Long id,
            @RequestBody NewsSourceAdministration.NewsRuleUpsert request) {
        return ApiResponse.success(sources.saveRule(NEWS_CAPABILITY_ID, id, request));
    }
}
