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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;

import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/licenses")
public class LicenseCenterController {
    private final LicenseIssuanceService issuanceService;
    private final LicenseModuleCatalogService moduleCatalogService;
    private final LicenseAuditService auditService;
    private final LicenseDeliveryPackageService deliveryPackageService;

    public LicenseCenterController(LicenseIssuanceService issuanceService,
                                   LicenseModuleCatalogService moduleCatalogService,
                                   LicenseAuditService auditService,
                                   LicenseDeliveryPackageService deliveryPackageService) {
        this.issuanceService = issuanceService;
        this.moduleCatalogService = moduleCatalogService;
        this.auditService = auditService;
        this.deliveryPackageService = deliveryPackageService;
    }

    @PostMapping("/issue")
    public IssuedLicense issue(@RequestBody LicensePayload payload, Authentication authentication) {
        byte[] content = issuanceService.issue(payload);
        var delivery = deliveryPackageService.create(content, payload.licenseNo());
        var audit = auditService.recordIssued(payload, content, authentication == null ? null : authentication.getName());
        return new IssuedLicense(delivery.fileName(), delivery.contentType(),
            Base64.getEncoder().encodeToString(delivery.content()), audit.id(), audit.issuedAt());
    }

    @GetMapping("/audits")
    public LicenseAuditService.LicenseAuditPage audits(
        @RequestParam(value = "keyword", defaultValue = "") String keyword,
        @RequestParam(value = "status", defaultValue = "") String status,
        @RequestParam(value = "edition", defaultValue = "") String edition,
        @RequestParam(value = "module", defaultValue = "") String module,
        @RequestParam(value = "dateFrom", required = false) java.time.LocalDate dateFrom,
        @RequestParam(value = "dateTo", required = false) java.time.LocalDate dateTo,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size) {
        return auditService.search(keyword, status, edition, module, dateFrom, dateTo, page, size);
    }

    @PostMapping("/audits/{id}/downloaded")
    public LicenseAuditService.LicenseAuditRecord downloaded(@PathVariable("id") String id) {
        return auditService.markDownloaded(id);
    }

    @GetMapping("/mcp-menus")
    public java.util.List<LicenseModuleCatalogService.MenuModule> mcpMenus() {
        return moduleCatalogService.listEnabled();
    }

    @PostMapping("/mcp-menus")
    public LicenseModuleCatalogService.MenuModule saveMcpMenu(
        @RequestBody LicenseModuleCatalogService.MenuModule module) {
        return moduleCatalogService.save(module);
    }

    @ExceptionHandler(LicenseException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> licenseError(LicenseException ex) {
        return Map.of("success", false, "message", ex.getMessage());
    }

    public record IssuedLicense(String fileName, String contentType, String contentBase64,
                                String recordId, java.time.Instant issuedAt) { }
}
