package com.chatchat.runtime.news.internet;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
/** Existing signed News control plane only; never a public unauthenticated URL proxy. */
@RestController
@RequestMapping("/internal/v1/news/internet-search")
public class InternetSearchController {
    private final InternetProviderStore store;private final InternetSearchService search;
    public InternetSearchController(InternetProviderStore store,InternetSearchService search){this.store=store;this.search=search;}
    @GetMapping("/providers") public ApiResponse<?> providers(){return ApiResponse.success(store.views());}
    @PostMapping("/providers") public ApiResponse<?> save(@RequestBody InternetProviderStore.Draft draft){return ApiResponse.success(store.save(draft));}
    @DeleteMapping("/providers/{id}") public ApiResponse<?> delete(@PathVariable("id") String id){store.delete(id);return ApiResponse.success(null);}
    @PostMapping("/read") public ApiResponse<?> read(@RequestBody JsonNode request){return ApiResponse.success(search.execute(request));}
}
