package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.uiartifact.TrendSemanticConfigService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/ui-display")
public class UiDisplayConfigController {

    private final TrendSemanticConfigService configService;

    @GetMapping("/trend-semantics")
    public ApiResponse<TrendSemanticConfigService.TrendSemanticConfig> trendSemantics(HttpServletRequest request) {
        return ApiResponse.success(configService.get(currentTenantId(request)));
    }

    @PutMapping("/trend-semantics")
    public ApiResponse<TrendSemanticConfigService.TrendSemanticConfig> updateTrendSemantics(
        @RequestBody TrendSemanticConfigService.UpdateRequest update,
        HttpServletRequest request
    ) {
        try {
            return ApiResponse.success(configService.update(currentTenantId(request), update));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @DeleteMapping("/trend-semantics")
    public ApiResponse<TrendSemanticConfigService.TrendSemanticConfig> resetTrendSemantics(HttpServletRequest request) {
        return ApiResponse.success(configService.reset(currentTenantId(request)));
    }

    private String currentTenantId(HttpServletRequest request) {
        Object tenantId = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        return tenantId == null || String.valueOf(tenantId).isBlank() ? "default" : String.valueOf(tenantId).trim();
    }
}
