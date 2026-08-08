package com.chatchat.mcpserver.license;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.license.LicenseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/license")
public class LicenseInternalController {

    private final McpLicenseService licenseService;

    @GetMapping("/agent-publication-limit")
    public ApiResponse<AgentPublicationLimit> agentPublicationLimit() {
        LicenseStatus status = licenseService.status();
        Integer maximum = status != null && status.license() != null ? status.license().maxAgents() : null;
        return ApiResponse.success(new AgentPublicationLimit(
            status != null && status.valid(),
            status == null ? "INVALID" : status.status(),
            status == null ? "License status unavailable" : status.message(),
            maximum,
            maximum != null
        ));
    }

    public record AgentPublicationLimit(boolean licenseValid, String licenseStatus, String message,
                                        Integer maxPublishedAgents, boolean limited) {
    }
}
