package com.chatchat.api.datascience.skill;

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
        when(repository.countVisibleByStatus("tenant-a", "PUBLISHED")).thenReturn(2L);
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
        when(repository.countVisibleByStatus("tenant-a", "PUBLISHED")).thenReturn(5L);
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
        when(repository.findVisibleByIdInAndStatus("tenant-a", List.of("skill-1", "missing"), "PUBLISHED"))
            .thenReturn(List.of(skill));

        var resolved = service(repository, mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class))
            .resolvePublished("tenant-a", List.of("skill-1", "missing", "skill-1"));

        assertThat(resolved).extracting(item -> item.id()).containsExactly("skill-1");
        verify(repository).findVisibleByIdInAndStatus("tenant-a", List.of("skill-1", "missing"), "PUBLISHED");
    }

    @Test
    void createsAnEmptyTenantCategory() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillCategoryRepository categories = mock(DomainSkillCategoryRepository.class);
        when(categories.findByTenantIdAndNameIgnoreCase("tenant-a", "Finance")).thenReturn(Optional.empty());
        when(categories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DomainSkillService service = new DomainSkillService(repository, categories,
            mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class));
        DomainSkillService.CategoryOption created = service.createCategory("tenant-a", " Finance ");

        assertThat(created.name()).isEqualTo("Finance");
        assertThat(created.count()).isZero();
        verify(categories).save(argThat(category -> "tenant-a".equals(category.getTenantId())
            && "Finance".equals(category.getName())));
    }

    @Test
    void rebuildsOnePublishedSkillIndexWithoutChangingPublicationState() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillIndexService index = mock(DomainSkillIndexService.class);
        DomainSkillEntity skill = skill("skill-1", "Finance", "# Finance");
        skill.setStatus("PUBLISHED");
        when(repository.findVisibleById("tenant-a", "skill-1")).thenReturn(Optional.of(skill));
        when(index.index(skill)).thenReturn(new DomainSkillIndexService.IndexResult(true, "BM25_KNN", ""));

        DomainSkillService.ReindexResult result = service(repository, mock(McpLicenseEntitlementPort.class), index)
            .reindex("tenant-a", "skill-1");

        assertThat(result.mode()).isEqualTo("BM25_KNN");
        assertThat(skill.getStatus()).isEqualTo("PUBLISHED");
        verify(index).index(skill);
        verify(repository, never()).save(any());
    }

    @Test
    void categoryRebuildSkipsSkillsWithUnpublishedChanges() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillIndexService index = mock(DomainSkillIndexService.class);
        DomainSkillEntity clean = skill("skill-clean", "Clean", "# Clean");
        clean.setStatus("PUBLISHED");
        DomainSkillEntity dirty = skill("skill-dirty", "Dirty", "# Dirty");
        dirty.setStatus("PUBLISHED");
        dirty.setPublicationDirty(true);
        when(repository.findVisibleByCategoryAndStatus("tenant-a", "General", "PUBLISHED"))
            .thenReturn(List.of(clean, dirty));
        when(index.index(clean)).thenReturn(new DomainSkillIndexService.IndexResult(true, "BM25", ""));

        DomainSkillService.CategoryReindexResult result = service(
            repository, mock(McpLicenseEntitlementPort.class), index).reindexCategory("tenant-a", "General");

        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.reindexed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        verify(index, never()).index(dirty);
    }

    private DomainSkillService service(DomainSkillRepository r, McpLicenseEntitlementPort e, DomainSkillIndexService i) {
        return new DomainSkillService(r, mock(DomainSkillCategoryRepository.class), e, i);
    }
    private DomainSkillEntity skill(String id, String name, String markdown) { DomainSkillEntity s = new DomainSkillEntity(); s.setId(id); s.setTenantId("tenant-a"); s.setOwnerId("admin"); s.setName(name); s.setCategory("General"); s.setMarkdownContent(markdown); s.setStatus("DRAFT"); s.setSourceType("EDITOR"); return s; }
    private byte[] zip(String... entries) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) { for (int i = 0; i < entries.length; i += 2) { zip.putNextEntry(new ZipEntry(entries[i])); zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); } } return out.toByteArray(); }
}
