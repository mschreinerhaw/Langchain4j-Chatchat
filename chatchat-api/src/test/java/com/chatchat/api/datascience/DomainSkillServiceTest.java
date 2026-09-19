package com.chatchat.api.datascience;

import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DomainSkillServiceTest {
    @Test
    void fallsBackToFivePublishedSkillsWhenMcpIsUnavailable() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        McpLicenseEntitlementPort entitlement = mock(McpLicenseEntitlementPort.class);
        when(entitlement.skillPublicationLimit()).thenThrow(new IllegalStateException("offline"));
        when(repository.countByTenantIdAndStatus("tenant-a", "PUBLISHED")).thenReturn(2L);
        DomainSkillService.PublicationQuota quota = service(repository, entitlement, mock(DomainSkillIndexService.class)).quota("tenant-a");
        assertThat(quota.maximum()).isEqualTo(5);
        assertThat(quota.remaining()).isEqualTo(3);
        assertThat(quota.source()).isEqualTo("DEFAULT");
    }

    @Test
    void publishesIntoDedicatedSkillIndex() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        McpLicenseEntitlementPort entitlement = mock(McpLicenseEntitlementPort.class);
        DomainSkillIndexService index = mock(DomainSkillIndexService.class);
        DomainSkillEntity skill = skill("skill-1", "Research", "# Research");
        when(repository.findByIdAndTenantId("skill-1", "tenant-a")).thenReturn(Optional.of(skill));
        when(entitlement.skillPublicationLimit()).thenReturn(new McpLicenseEntitlementPort.SkillPublicationLimit(true, "VALID", "", 5, true, "MCP"));
        when(index.index(skill)).thenReturn(new DomainSkillIndexService.IndexResult(true, "BM25", ""));
        DomainSkillEntity published = service(repository, entitlement, index).publish("tenant-a", "skill-1");
        verify(index).index(skill);
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void refusesPublicationAtTenantLicenseLimit() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        McpLicenseEntitlementPort entitlement = mock(McpLicenseEntitlementPort.class);
        when(repository.findByIdAndTenantId("skill-6", "tenant-a")).thenReturn(Optional.of(skill("skill-6", "Six", "# Six")));
        when(repository.countByTenantIdAndStatus("tenant-a", "PUBLISHED")).thenReturn(5L);
        when(entitlement.skillPublicationLimit()).thenReturn(new McpLicenseEntitlementPort.SkillPublicationLimit(true, "DEFAULT", "", 5, true, "DEFAULT"));
        assertThatThrownBy(() -> service(repository, entitlement, mock(DomainSkillIndexService.class)).publish("tenant-a", "skill-6"))
            .hasMessageContaining("SKILL_LICENSE_LIMIT_EXCEEDED");
    }

    @Test
    void importsSkillMdFromZipAndAssignsCategory() throws Exception {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "skill.zip", "application/zip",
            zip("README.md", "# Readme", "package/SKILL.md", "# Risk Analysis\n\nValidate risk."));
        DomainSkillEntity imported = service(repository, mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class))
            .importFile("tenant-a", "admin", file, "", "Risk");
        assertThat(imported.getName()).isEqualTo("Risk Analysis");
        assertThat(imported.getCategory()).isEqualTo("Risk");
        assertThat(imported.getMarkdownContent()).doesNotContain("Readme");
    }

    @Test
    void resolvesOnlyPublishedSkillsInsideTheRequestedTenant() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillEntity skill = skill("skill-1", "Finance", "Use Decimal for money.");
        skill.setStatus("PUBLISHED");
        when(repository.findByTenantIdAndIdInAndStatus("tenant-a", List.of("skill-1", "missing"), "PUBLISHED"))
            .thenReturn(List.of(skill));

        var resolved = service(repository, mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class))
            .resolvePublished("tenant-a", List.of("skill-1", "missing", "skill-1"));

        assertThat(resolved).extracting(item -> item.id()).containsExactly("skill-1");
        verify(repository).findByTenantIdAndIdInAndStatus("tenant-a", List.of("skill-1", "missing"), "PUBLISHED");
    }

    private DomainSkillService service(DomainSkillRepository r, McpLicenseEntitlementPort e, DomainSkillIndexService i) { return new DomainSkillService(r, e, i); }
    private DomainSkillEntity skill(String id, String name, String markdown) { DomainSkillEntity s = new DomainSkillEntity(); s.setId(id); s.setTenantId("tenant-a"); s.setOwnerId("admin"); s.setName(name); s.setCategory("General"); s.setMarkdownContent(markdown); s.setStatus("DRAFT"); s.setSourceType("EDITOR"); return s; }
    private byte[] zip(String... entries) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) { for (int i = 0; i < entries.length; i += 2) { zip.putNextEntry(new ZipEntry(entries[i])); zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); } } return out.toByteArray(); }
}
