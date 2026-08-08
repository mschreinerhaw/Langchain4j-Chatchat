package com.chatchat.license.server;

import com.chatchat.license.LicenseDocument;
import com.chatchat.license.LicensePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "chatchat.license-center.password=test-only-password",
    "spring.datasource.url=jdbc:h2:mem:license_audit_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.password=Test-H2_Audit#2026!Secure"
})
class LicenseAuditServiceTest {
    @Autowired
    LicenseAuditService auditService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void persistsIssuanceAndDownloadAuditInH2() throws Exception {
        LicensePayload payload = new LicensePayload(
            "LIC-AUDIT-1", null, "ORG-1", "LiveMCP", "enterprise",
            List.of("assetJmx", "databaseMcp"), 500, 100, "MAC-AABBCCDDEEFF",
            LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        LicenseDocument document = new LicenseDocument(
            LicenseDocument.FORMAT, LicenseDocument.ALGORITHM, "prod-key", payload, "test-signature");

        var issued = auditService.recordIssued(payload, objectMapper.writeValueAsBytes(document), "license-operator");
        var delivered = auditService.markDownloaded(issued.id());

        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.documentSha256()).hasSize(64);
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(delivered.downloadCount()).isEqualTo(1);
        assertThat(delivered.lastDownloadedAt()).isNotNull();
        assertThat(auditService.search("ORG-1", "DELIVERED", "enterprise", "assetJmx",
            LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), 0, 20).content())
            .extracting(LicenseAuditService.LicenseAuditRecord::licenseNo)
            .containsExactly("LIC-AUDIT-1");
    }

    @Test
    void paginatesAndFiltersAuditRecordsByEditionAndModule() throws Exception {
        for (int index = 0; index < 23; index++) {
            LicensePayload payload = new LicensePayload(
                "LIC-PAGE-" + index, null, "PAGE-ORG", "LiveMCP",
                index % 2 == 0 ? "enterprise" : "professional",
                index % 3 == 0 ? List.of("assetJmx") : List.of("assetSsh"),
                100, 20, "MAC-AABBCCDDEEFF", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
            LicenseDocument document = new LicenseDocument(
                LicenseDocument.FORMAT, LicenseDocument.ALGORITHM, "page-key", payload, "test-signature");
            auditService.recordIssued(payload, objectMapper.writeValueAsBytes(document), "page-operator");
        }

        var firstPage = auditService.search("LIC-PAGE-", "", "", "", null, null, 0, 10);
        var filtered = auditService.search("LIC-PAGE-", "ISSUED", "enterprise", "assetJmx",
            null, null, 0, 20);

        assertThat(firstPage.content()).hasSize(10);
        assertThat(firstPage.totalElements()).isEqualTo(23);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(filtered.content()).isNotEmpty()
            .allSatisfy(item -> {
                assertThat(item.edition()).isEqualTo("enterprise");
                assertThat(item.modules()).contains("assetJmx");
            });
    }
}
