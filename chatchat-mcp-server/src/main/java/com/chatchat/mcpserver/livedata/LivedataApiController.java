package com.chatchat.mcpserver.livedata;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/livedata-apis")
public class LivedataApiController {

    private final LivedataApiRegistrationService registrationService;
    private final LivedataConfigService configService;

    /**
     * Lists the list.
     *
     * @return the list list
     */
    @GetMapping
    public ApiResponse<List<LivedataApiRegistrationService.LivedataApiCandidate>> list() {
        return ApiResponse.success(registrationService.listCandidates());
    }

    /**
     * Registers the register.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/register")
    public ApiResponse<LivedataApiRegistrationService.LivedataRegistrationResult> register(
        @RequestBody LivedataRegisterRequest request) {
        LivedataApiRegistrationService.LivedataRegistrationResult result =
            registrationService.register(request.ids(), request.overwriteExisting());
        return ApiResponse.success(result, "LiveData API manual registration completed");
    }

    @GetMapping("/config")
    public ApiResponse<LivedataConfigView> getConfig() {
        return ApiResponse.success(toView(configService.getConfig()));
    }

    @PutMapping("/config")
    public ApiResponse<LivedataConfigView> saveConfig(@RequestBody LivedataConfigRequest request) {
        return ApiResponse.success(toView(configService.save(fromRequest(request))), "LiveData config saved");
    }

    private LivedataConfig fromRequest(LivedataConfigRequest request) {
        LivedataConfig config = new LivedataConfig();
        config.setEnabled(request.enabled() != null && request.enabled());
        config.setDatasourceId(request.datasourceId());
        config.setTableName(request.tableName());
        config.setServiceBaseUrl(request.serviceBaseUrl());
        config.setServicePathTemplate(request.servicePathTemplate());
        config.setLoginEnabled(request.loginEnabled() == null || request.loginEnabled());
        config.setLoginPath(request.loginPath());
        config.setLoginId(request.loginId());
        config.setLoginPwd(request.loginPwd());
        config.setLoginTimeoutMs(request.loginTimeoutMs() == null ? 10000 : request.loginTimeoutMs());
        config.setSessionTtlSeconds(request.sessionTtlSeconds() == null ? 1800 : request.sessionTtlSeconds());
        config.setAmsToken(request.amsToken());
        config.setDefaultNamespace(request.defaultNamespace());
        config.setToolNamePrefix(request.toolNamePrefix());
        config.setPublishedState(request.publishedState() == null ? 0 : request.publishedState());
        config.setMaxApis(request.maxApis() == null ? 1000 : request.maxApis());
        config.setTimeoutMs(request.timeoutMs() == null ? 20000 : request.timeoutMs());
        config.setCacheEnabled(request.cacheEnabled() != null && request.cacheEnabled());
        config.setCacheTtlSeconds(request.cacheTtlSeconds() == null ? 300 : request.cacheTtlSeconds());
        config.setOverwriteExisting(request.overwriteExisting() != null && request.overwriteExisting());
        config.setIncludeUnpublishedAsDisabled(request.includeUnpublishedAsDisabled() != null && request.includeUnpublishedAsDisabled());
        config.setExposeAmsTokenParameter(request.exposeAmsTokenParameter() != null && request.exposeAmsTokenParameter());
        return config;
    }

    private LivedataConfigView toView(LivedataConfig config) {
        return new LivedataConfigView(
            config.isEnabled(),
            config.getDatasourceId(),
            config.getTableName(),
            config.getServiceBaseUrl(),
            config.getServicePathTemplate(),
            config.isLoginEnabled(),
            config.getLoginPath(),
            config.getLoginId(),
            config.getLoginPwd(),
            config.getLoginTimeoutMs(),
            config.getSessionTtlSeconds(),
            config.getAmsToken(),
            config.getDefaultNamespace(),
            config.getToolNamePrefix(),
            config.getPublishedState(),
            config.getMaxApis(),
            config.getTimeoutMs(),
            config.isCacheEnabled(),
            config.getCacheTtlSeconds(),
            config.isOverwriteExisting(),
            config.isIncludeUnpublishedAsDisabled(),
            config.isExposeAmsTokenParameter()
        );
    }

    public record LivedataRegisterRequest(List<String> ids, Boolean overwriteExisting) {
    }

    public record LivedataConfigRequest(
        Boolean enabled,
        String datasourceId,
        String tableName,
        String serviceBaseUrl,
        String servicePathTemplate,
        Boolean loginEnabled,
        String loginPath,
        String loginId,
        String loginPwd,
        Integer loginTimeoutMs,
        Integer sessionTtlSeconds,
        String amsToken,
        String defaultNamespace,
        String toolNamePrefix,
        Integer publishedState,
        Integer maxApis,
        Integer timeoutMs,
        Boolean cacheEnabled,
        Integer cacheTtlSeconds,
        Boolean overwriteExisting,
        Boolean includeUnpublishedAsDisabled,
        Boolean exposeAmsTokenParameter
    ) {
    }

    public record LivedataConfigView(
        boolean enabled,
        String datasourceId,
        String tableName,
        String serviceBaseUrl,
        String servicePathTemplate,
        boolean loginEnabled,
        String loginPath,
        String loginId,
        String loginPwd,
        int loginTimeoutMs,
        int sessionTtlSeconds,
        String amsToken,
        String defaultNamespace,
        String toolNamePrefix,
        int publishedState,
        int maxApis,
        int timeoutMs,
        boolean cacheEnabled,
        int cacheTtlSeconds,
        boolean overwriteExisting,
        boolean includeUnpublishedAsDisabled,
        boolean exposeAmsTokenParameter
    ) {
    }
}
