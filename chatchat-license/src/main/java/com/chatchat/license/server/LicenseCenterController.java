package com.chatchat.license.server;

import com.chatchat.license.LicenseException;
import com.chatchat.license.LicensePayload;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/licenses")
public class LicenseCenterController {
    private final LicenseIssuanceService issuanceService;
    private final McpMenuCatalogClient menuCatalogClient;

    public LicenseCenterController(LicenseIssuanceService issuanceService, McpMenuCatalogClient menuCatalogClient) {
        this.issuanceService = issuanceService;
        this.menuCatalogClient = menuCatalogClient;
    }

    @PostMapping("/issue")
    public IssuedLicense issue(@RequestBody LicensePayload payload) {
        byte[] content = issuanceService.issue(payload);
        return new IssuedLicense("license.dat", Base64.getEncoder().encodeToString(content));
    }

    @GetMapping("/mcp-menus")
    public java.util.List<McpMenuCatalogClient.MenuModule> mcpMenus() {
        return menuCatalogClient.load();
    }

    @ExceptionHandler(LicenseException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> licenseError(LicenseException ex) {
        return Map.of("success", false, "message", ex.getMessage());
    }

    public record IssuedLicense(String fileName, String contentBase64) { }
}
