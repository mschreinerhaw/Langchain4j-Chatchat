package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.uiartifact.UiArtifactService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/ui-artifacts")
public class UiArtifactController {

    private final UiArtifactService artifactService;

    @GetMapping("/{artifactId}")
    public ApiResponse<Object> manifest(@PathVariable String artifactId, HttpServletRequest request) {
        return artifactService.manifest(currentTenantId(request), artifactId)
            .<ApiResponse<Object>>map(ApiResponse::success)
            .orElseGet(() -> ApiResponse.notFound("UI artifact not found: " + artifactId));
    }

    @GetMapping("/{artifactId}/resources/{resourceId}")
    public ApiResponse<Object> resource(@PathVariable String artifactId,
                                        @PathVariable String resourceId,
                                        HttpServletRequest request) {
        return artifactService.resource(currentTenantId(request), artifactId, resourceId)
            .map(ApiResponse::success)
            .orElseGet(() -> ApiResponse.notFound("UI artifact resource not found: " + resourceId));
    }

    @DeleteMapping("/{artifactId}")
    public ApiResponse<Object> delete(@PathVariable String artifactId, HttpServletRequest request) {
        if (!artifactService.delete(currentTenantId(request), artifactId)) {
            return ApiResponse.notFound("UI artifact not found: " + artifactId);
        }
        return ApiResponse.success(java.util.Map.of("artifactId", artifactId, "status", "DELETED"));
    }

    private String currentTenantId(HttpServletRequest request) {
        Object tenantId = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        return tenantId == null || String.valueOf(tenantId).isBlank() ? "default" : String.valueOf(tenantId).trim();
    }
}
