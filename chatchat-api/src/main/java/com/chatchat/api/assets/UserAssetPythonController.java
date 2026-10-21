package com.chatchat.api.assets;

import com.chatchat.api.datascience.python.PythonDataScienceService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Data-science capability only, deliberately not exposed through the chat upload alias. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/data-science/user-assets")
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class UserAssetPythonController {
    private final UserAssetService assets;private final ObjectAccessService access;private final PythonDataScienceService python;
    @PostMapping("/{id}/python-data") public ApiResponse<?> prepare(HttpServletRequest http,@PathVariable("id") String id){
        var owner=new UserAssetService.Owner(access.tenant(http),access.user(http));var file=assets.get(owner,id);
        if(!"DOCUMENT".equals(file.kind()))throw new IllegalArgumentException("Select an original data file");
        return ApiResponse.success(python.prepareUserSpaceData(owner.tenant(),access.legacyUser(http),id,file.fileName(),assets.read(owner,id)));
    }
}
