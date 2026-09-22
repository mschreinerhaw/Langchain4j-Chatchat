package com.chatchat.chat.skills.domain;

import com.chatchat.chat.skills.domain.adapter.ExternalSkillAdapterGateway;
import com.chatchat.chat.skills.domain.adapter.ExternalSkillCompilation;
import com.chatchat.chat.skills.domain.adapter.RuntimeSkillIr;
import com.chatchat.chat.skills.domain.adapter.SkillMdExternalSkillAdapter;
import com.chatchat.chat.skills.domain.adapter.SkillFormatDetector;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
        byte[] content = zip("README.md", "# Readme", "package/SKILL.md", "# Risk Analysis\n\nValidate risk.");
        DomainSkillEntity imported = service(repository, mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class))
            .importFile("tenant-a", "admin", content, "skill.zip", "", "Risk");
        assertThat(imported.getName()).isEqualTo("Risk Analysis");
        assertThat(imported.getCategory()).isEqualTo("Risk");
        assertThat(imported.getMarkdownContent()).doesNotContain("Readme");
    }

    @Test
    void normalizesOpenSourceSkillMdFrontMatterIntoPlatformFields() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String markdown = """
            ---
            name: china-idea-generation
            description: >-
              Systematic stock screening and investment idea sourcing for A-share markets.
              Triggers on stock screen China or A-share ideas.
            triggers:
              - stock screen China
            allowed-tools:
              - external_market_tool
            ---

            # China Idea Generation

            Use quantitative screens and verify every material claim.
            """;
        DomainSkillEntity imported = service(repository, mock(McpLicenseEntitlementPort.class),
            mock(DomainSkillIndexService.class)).importFile("tenant-a", "admin",
                markdown.getBytes(StandardCharsets.UTF_8), "SKILL.md", "", "Investment Research");

        assertThat(imported.getName()).isEqualTo("china-idea-generation");
        assertThat(imported.getDescription())
            .isEqualTo("Systematic stock screening and investment idea sourcing for A-share markets. "
                + "Triggers on stock screen China or A-share ideas.");
        assertThat(imported.getMarkdownContent())
            .startsWith("# China Idea Generation")
            .contains("Use quantitative screens")
            .doesNotContain("allowed-tools", "external_market_tool", "triggers:");
        assertThat(imported.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void explicitImportNameOverridesOpenSourceFrontMatterName() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        byte[] content = "---\nname: upstream-name\ndescription: Upstream description\n---\n# Body"
            .getBytes(StandardCharsets.UTF_8);

        DomainSkillEntity imported = service(repository, mock(McpLicenseEntitlementPort.class),
            mock(DomainSkillIndexService.class)).importFile("tenant-a", "admin", content,
                "SKILL.md", "Platform Name", "Research");

        assertThat(imported.getName()).isEqualTo("Platform Name");
        assertThat(imported.getDescription()).isEqualTo("Upstream description");
    }

    @Test
    void rejectsMalformedOpenSourceSkillFrontMatter() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        byte[] content = "---\nname: [invalid\n---\n# Body".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service(repository, mock(McpLicenseEntitlementPort.class),
            mock(DomainSkillIndexService.class)).importFile("tenant-a", "admin", content,
                "SKILL.md", "", "Research"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid SKILL.md front matter");
        verify(repository, never()).save(any());
    }

    @Test
    void importsPublicInternetSkillAsDraft() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillCategoryRepository categories = mock(DomainSkillCategoryRepository.class);
        DomainSkillRemoteImporter remote = mock(DomainSkillRemoteImporter.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(remote.download("https://skills.example/research/SKILL.md"))
            .thenReturn(new DomainSkillRemoteImporter.RemoteFile(
                "https://skills.example/research/SKILL.md", "SKILL.md",
                "# Internet Research\n\nVerify every source.".getBytes(StandardCharsets.UTF_8)));

        DomainSkillEntity imported = new DomainSkillService(repository, categories,
            mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class), remote,
            externalSkillGateway(), mock(DomainSkillArtifactStore.class))
            .importUrl("tenant-a", "admin", "https://skills.example/research/SKILL.md", "", "Research");

        assertThat(imported.getName()).isEqualTo("Internet Research");
        assertThat(imported.getStatus()).isEqualTo("DRAFT");
        assertThat(imported.getSourceType()).isEqualTo("URL_MARKDOWN");
        assertThat(imported.getDescription()).contains("https://skills.example/research/SKILL.md");
    }

    @Test
    void persistsOnlyThePlatformIrProducedByTheExternalSkillGateway() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        ExternalSkillAdapterGateway gateway = mock(ExternalSkillAdapterGateway.class);
        DomainSkillArtifactStore artifactStore = mock(DomainSkillArtifactStore.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeSkillIr ir = new RuntimeSkillIr(
            RuntimeSkillIr.SCHEMA_VERSION, "compiled-skill", "平台归一化说明",
            "# Compiled\n\n只使用 Runtime 已授权的数据与工具。", List.of("证券分析"),
            List.of("必须核验数据来源"), "MODEL");
        ExternalSkillCompilation compilation = new ExternalSkillCompilation(ir, "raw".getBytes(StandardCharsets.UTF_8),
            "hash", "{}", "{}", "SKILL.md");
        when(gateway.adaptAndCompile(any())).thenReturn(compilation);
        byte[] content = "---\nname: upstream\nallowed-tools: [anything]\n---\n# Raw"
            .getBytes(StandardCharsets.UTF_8);
        DomainSkillService service = new DomainSkillService(repository,
            mock(DomainSkillCategoryRepository.class), mock(McpLicenseEntitlementPort.class),
            mock(DomainSkillIndexService.class), mock(DomainSkillRemoteImporter.class), gateway, artifactStore);

        DomainSkillEntity imported = service.importFile("tenant-a", "admin", content,
            "SKILL.md", "", "Research");

        assertThat(imported.getName()).isEqualTo("compiled-skill");
        assertThat(imported.getDescription()).isEqualTo("平台归一化说明");
        assertThat(imported.getMarkdownContent()).isEqualTo("# Compiled\n\n只使用 Runtime 已授权的数据与工具。");
        verify(gateway).adaptAndCompile(argThat(source ->
            "SKILL.md".equals(source.fileName())
                && "MARKDOWN".equals(source.sourceType())
                && source.content().contains("allowed-tools")));
        verify(artifactStore).store("tenant-a", imported.getId(), "MARKDOWN", "SKILL.md", compilation);
    }

    @Test
    void resolvesOnlyPublishedSkillsInsideTheRequestedTenant() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillEntity skill = skill("skill-1", "Finance", "Use Decimal for money.");
        skill.setStatus("PUBLISHED");
        when(repository.findVisibleByIdInAndStatus("tenant-a", List.of("skill-1", "missing"), "PUBLISHED"))
            .thenReturn(List.of(skill));
        when(repository.findVisibleById("tenant-a", "skill-1")).thenReturn(Optional.of(skill));

        var resolved = service(repository, mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class))
            .resolvePublished("tenant-a", List.of("skill-1", "missing", "skill-1"));

        assertThat(resolved).extracting(item -> item.id()).containsExactly("skill-1");
        verify(repository).findVisibleByIdInAndStatus("tenant-a", List.of("skill-1", "missing"), "PUBLISHED");
    }

    @Test
    void excludesPublishedSkillWithUnpublishedEditsFromRuntimeResolution() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillEntity skill = skill("skill-1", "Finance", "Unpublished replacement content");
        skill.setStatus("PUBLISHED");
        skill.setPublicationDirty(true);
        when(repository.findVisibleByIdInAndStatus("tenant-a", List.of("skill-1"), "PUBLISHED"))
            .thenReturn(List.of(skill));

        var resolved = service(repository, mock(McpLicenseEntitlementPort.class),
            mock(DomainSkillIndexService.class)).resolvePublished("tenant-a", List.of("skill-1"));

        assertThat(resolved).isEmpty();
    }

    @Test
    void semanticSkillRecallCannotReturnIdsOutsidePublishedDatabaseScope() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillIndexService index = mock(DomainSkillIndexService.class);
        DomainSkillEntity skill = skill("skill-1", "Finance", "Published finance guidance");
        skill.setStatus("PUBLISHED");
        when(repository.findVisibleByIdInAndStatus("tenant-a", List.of("skill-1", "missing"), "PUBLISHED"))
            .thenReturn(List.of(skill));
        when(repository.findVisibleById("tenant-a", "skill-1")).thenReturn(Optional.of(skill));
        when(index.searchIds(eq("finance"), anyList(), eq(2))).thenReturn(List.of("missing", "skill-1"));

        var resolved = service(repository, mock(McpLicenseEntitlementPort.class), index)
            .retrievePublished("tenant-a", "user-a", List.of(), "finance", List.of("skill-1", "missing"));

        assertThat(resolved).extracting(item -> item.id()).containsExactly("skill-1");
    }

    @Test
    void tenantSkillGrantsRequireAnExplicitRoleGrantForDomainSkills() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillEntity skill = skill("skill-1", "Finance", "Published guidance");
        skill.setStatus("PUBLISHED");
        when(repository.findVisibleByIdInAndStatus("tenant-a", List.of("skill-1"), "PUBLISHED"))
            .thenReturn(List.of(skill));
        ResourceAuthorizationPort grants = mock(ResourceAuthorizationPort.class);
        when(grants.hasConfiguredRules(ResourceAuthorizationPort.SKILL, "tenant-a")).thenReturn(true);
        DomainSkillService domainSkills = service(repository, mock(McpLicenseEntitlementPort.class),
            mock(DomainSkillIndexService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(domainSkills, "resourceAuthorization", grants);

        assertThat(domainSkills.retrievePublished("tenant-a", "user-a", List.of(), "finance",
            List.of("skill-1"))).isEmpty();
    }

    @Test
    void createsAnEmptyTenantCategory() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillCategoryRepository categories = mock(DomainSkillCategoryRepository.class);
        when(categories.findByTenantIdAndNameIgnoreCase("tenant-a", "Finance")).thenReturn(Optional.empty());
        when(categories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DomainSkillService service = new DomainSkillService(repository, categories,
            mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class),
            mock(DomainSkillRemoteImporter.class), externalSkillGateway(), mock(DomainSkillArtifactStore.class));
        DomainSkillService.CategoryOption created = service.createCategory("tenant-a", " Finance ");

        assertThat(created.name()).isEqualTo("Finance");
        assertThat(created.count()).isZero();
        assertThat(created.manageable()).isTrue();
        verify(categories).save(argThat(category -> "tenant-a".equals(category.getTenantId())
            && "Finance".equals(category.getName())));
    }

    @Test
    void renamesCategoryAndUpdatesItsPublishedSkillIndex() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillCategoryRepository categories = mock(DomainSkillCategoryRepository.class);
        DomainSkillIndexService index = mock(DomainSkillIndexService.class);
        DomainSkillCategoryEntity category = new DomainSkillCategoryEntity();
        category.setId("category-1"); category.setTenantId("tenant-a"); category.setName("Old Name");
        DomainSkillEntity skill = skill("skill-1", "Skill", "# Skill");
        skill.setCategory("Old Name"); skill.setStatus("PUBLISHED");
        when(categories.findByIdAndTenantId("category-1", "tenant-a")).thenReturn(Optional.of(category));
        when(categories.findByTenantIdAndNameIgnoreCase("tenant-a", "New Name")).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndCategoryIgnoreCase("tenant-a", "Old Name")).thenReturn(List.of(skill));
        when(index.index(skill)).thenReturn(new DomainSkillIndexService.IndexResult(true, "BM25", ""));

        DomainSkillService service = new DomainSkillService(repository, categories,
            mock(McpLicenseEntitlementPort.class), index, mock(DomainSkillRemoteImporter.class),
            externalSkillGateway(), mock(DomainSkillArtifactStore.class));
        DomainSkillService.CategoryOption renamed = service.renameCategory("tenant-a", "category-1", "New Name");

        assertThat(renamed.name()).isEqualTo("New Name");
        assertThat(skill.getCategory()).isEqualTo("New Name");
        verify(repository).saveAllAndFlush(List.of(skill));
        verify(index).index(skill);
    }

    @Test
    void refusesToDeleteCategoryThatStillContainsSkills() {
        DomainSkillRepository repository = mock(DomainSkillRepository.class);
        DomainSkillCategoryRepository categories = mock(DomainSkillCategoryRepository.class);
        DomainSkillCategoryEntity category = new DomainSkillCategoryEntity();
        category.setId("category-1"); category.setTenantId("tenant-a"); category.setName("Finance");
        when(categories.findByIdAndTenantId("category-1", "tenant-a")).thenReturn(Optional.of(category));
        when(repository.countByTenantIdAndCategoryIgnoreCase("tenant-a", "Finance")).thenReturn(2L);
        DomainSkillService service = new DomainSkillService(repository, categories,
            mock(McpLicenseEntitlementPort.class), mock(DomainSkillIndexService.class),
            mock(DomainSkillRemoteImporter.class), externalSkillGateway(), mock(DomainSkillArtifactStore.class));

        assertThatThrownBy(() -> service.deleteCategory("tenant-a", "category-1"))
            .hasMessageContaining("2 skill");
        verify(categories, never()).delete(any());
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
        return new DomainSkillService(r, mock(DomainSkillCategoryRepository.class), e, i,
            mock(DomainSkillRemoteImporter.class), externalSkillGateway(), mock(DomainSkillArtifactStore.class));
    }
    private ExternalSkillAdapterGateway externalSkillGateway() {
        return new ExternalSkillAdapterGateway(List.of(new SkillMdExternalSkillAdapter(new SkillFormatDetector())), skill ->
            new RuntimeSkillIr(RuntimeSkillIr.SCHEMA_VERSION, skill.name(), skill.description(),
                skill.instructions(), List.of(), List.of(), "TEST_DETERMINISTIC"), new ObjectMapper());
    }
    private DomainSkillEntity skill(String id, String name, String markdown) { DomainSkillEntity s = new DomainSkillEntity(); s.setId(id); s.setTenantId("tenant-a"); s.setOwnerId("admin"); s.setName(name); s.setCategory("General"); s.setMarkdownContent(markdown); s.setStatus("DRAFT"); s.setSourceType("EDITOR"); return s; }
    private byte[] zip(String... entries) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) { for (int i = 0; i < entries.length; i += 2) { zip.putNextEntry(new ZipEntry(entries[i])); zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); } } return out.toByteArray(); }
}
