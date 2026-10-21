package com.chatchat.api.assets;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/data-science/user-assets/knowledge")
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.assets.knowledge",name="enabled",havingValue="true")
public class AssetKnowledgeController {
    private final AssetKnowledgeService knowledge;
    private final ObjectAccessService access;
    private UserAssetService.Owner owner(HttpServletRequest request){return new UserAssetService.Owner(access.tenant(request),access.user(request));}
    @GetMapping public ApiResponse<?> statuses(HttpServletRequest request){return ApiResponse.success(knowledge.statuses(owner(request)));}
    @GetMapping("/search") public ApiResponse<?> search(HttpServletRequest request,@RequestParam("q") String query,
        @RequestParam(value="limit",defaultValue="10") int limit,@RequestParam(value="historyOnly",defaultValue="false") boolean historyOnly){
        return ApiResponse.success(knowledge.search(owner(request),query,limit,historyOnly));
    }
    @GetMapping("/{id}/status") public ApiResponse<?> status(HttpServletRequest request,@PathVariable("id") String id){return ApiResponse.success(knowledge.status(owner(request),id));}
    @PostMapping("/{id}/retry") public ApiResponse<?> retry(HttpServletRequest request,@PathVariable("id") String id){return ApiResponse.success(knowledge.retry(owner(request),id));}
    @PostMapping("/rebuild") public ApiResponse<?> rebuild(HttpServletRequest request){return ApiResponse.success(knowledge.rebuild(owner(request)));}
}
