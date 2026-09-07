package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.analysis.profile.DomainAnalysisProfileService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/analysis-profiles")
public class DomainAnalysisProfileController {
    private final DomainAnalysisProfileService profiles;
    @GetMapping
    public ApiResponse<List<DomainAnalysisProfileService.View>> list(HttpServletRequest request) {
        return ApiResponse.success(profiles.list(tenant(request)));
    }
    @PutMapping("/{analysisType}")
    public ApiResponse<DomainAnalysisProfileService.View> update(@PathVariable("analysisType") String analysisType,
        @RequestBody DomainAnalysisProfileService.Update update, HttpServletRequest request) {
        try { return ApiResponse.success(profiles.update(tenant(request), analysisType, update)); }
        catch (IllegalArgumentException invalid) { return ApiResponse.badRequest(invalid.getMessage()); }
    }
    private String tenant(HttpServletRequest request) {
        Object value = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        return value == null ? "default" : value.toString();
    }
}
