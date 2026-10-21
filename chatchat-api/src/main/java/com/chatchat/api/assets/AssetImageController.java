package com.chatchat.api.assets;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.image.ImageUnderstandingService;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/v1/data-science/user-assets","/api/v1/interactions/user-assets"})
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class AssetImageController {
    private final UserAssetService assets;
    private final ObjectAccessService access;
    private final ImageUnderstandingService images;
    private final ObjectMapper json;
    public record Request(String question,String modelName) { }
    @PostMapping("/{id}/image-analysis")
    public ApiResponse<?> analyze(HttpServletRequest http,@PathVariable("id") String id,@RequestBody Request request)throws java.io.IOException {
        var owner=new UserAssetService.Owner(access.tenant(http),access.user(http));
        var file=assets.get(owner,id);
        if(!file.contentType().startsWith("image/") || file.size()>10*1024*1024)throw new IllegalArgumentException("Select an image of at most 10 MiB");
        var view=images.toAnalysisView(images.analyzeStoredBytes(file.id(),owner.tenant(),owner.user(),file.fileName(),file.contentType(),file.sha256(),
            assets.read(owner,id),request==null?null:request.question(),request==null?null:request.modelName()));
        assets.store(owner,UserAssetService.Kind.CONTEXT,"","","image-analysis:"+file.id()+":"+view.id(),
            "image-analysis.json","application/json",json.writeValueAsBytes(view));
        return ApiResponse.success(view);
    }
}
