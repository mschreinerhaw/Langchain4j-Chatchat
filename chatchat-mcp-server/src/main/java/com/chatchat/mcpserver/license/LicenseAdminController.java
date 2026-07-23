package com.chatchat.mcpserver.license;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.license.LicensePayload;
import com.chatchat.license.LicenseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/license")
public class LicenseAdminController {
    private final McpLicenseService licenseService;

    @GetMapping("/status")
    public ApiResponse<LicenseAdminView> status() {
        return ApiResponse.success(view(licenseService.status()));
    }

    private LicenseAdminView view(LicenseStatus status) {
        return new LicenseAdminView(
            status.valid(), status.status(), status.message(), status.serverId(), licenseService.macAddresses(),
            status.license(), licenseService.enforcementEnabled()
        );
    }

    public record LicenseAdminView(boolean valid, String status, String message, String serverId,
                                   java.util.List<String> macAddresses, LicensePayload license,
                                   boolean enforcementEnabled) { }
}
