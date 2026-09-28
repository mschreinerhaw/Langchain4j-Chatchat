package com.chatchat.api.migration;

import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.release.AgentReleaseService;
import com.chatchat.enterprise.entity.identity.SysTenant;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.service.SkillResourceScopeSynchronizationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacySkillResourceScopeMigrationTest {

    @Test
    void migratesStoredAgentKnowledgeBindingsForEnabledTenants() {
        SkillCatalogService skills = mock(SkillCatalogService.class);
        AgentReleaseService releases = mock(AgentReleaseService.class);
        SysTenantRepository tenants = mock(SysTenantRepository.class);
        SkillResourceScopeSynchronizationService scopes =
            mock(SkillResourceScopeSynchronizationService.class);
        SysTenant tenant = new SysTenant();
        tenant.setId("tenant-1");
        tenant.setStatus("enabled");
        SkillDefinition skill = mock(SkillDefinition.class);
        when(skill.id()).thenReturn("live-data");
        when(skill.boundDocumentIds()).thenReturn(List.of("doc-1"));
        when(skill.boundDocumentTags()).thenReturn(List.of("livedata"));
        when(tenants.findAllByOrderByTenantNameAsc()).thenReturn(List.of(tenant));
        when(skills.list()).thenReturn(List.of(skill));

        new LegacySkillResourceScopeMigration(skills, releases, tenants, scopes).migrate();

        verify(scopes).migrateIfMissing("tenant-1", "live-data", List.of("doc-1"), List.of("livedata"));
    }

    @Test
    void migratesPublishedAgentBindingsFromItsImmutableRelease() {
        SkillCatalogService skills = mock(SkillCatalogService.class);
        AgentReleaseService releases = mock(AgentReleaseService.class);
        SysTenantRepository tenants = mock(SysTenantRepository.class);
        SkillResourceScopeSynchronizationService scopes =
            mock(SkillResourceScopeSynchronizationService.class);
        SysTenant tenant = new SysTenant();
        tenant.setId("tenant-1");
        tenant.setStatus("enabled");
        SkillDefinition current = mock(SkillDefinition.class);
        when(current.id()).thenReturn("live-data");
        when(current.marketStatus()).thenReturn(SkillCatalogService.MARKET_STATUS_PUBLISHED);
        SkillDefinition published = mock(SkillDefinition.class);
        when(published.boundDocumentIds()).thenReturn(List.of("published-doc"));
        when(published.boundDocumentTags()).thenReturn(List.of("published-library"));
        when(tenants.findAllByOrderByTenantNameAsc()).thenReturn(List.of(tenant));
        when(skills.list()).thenReturn(List.of(current));
        when(releases.resolvePublished("live-data")).thenReturn(Optional.of(published));

        new LegacySkillResourceScopeMigration(skills, releases, tenants, scopes).migrate();

        verify(scopes).migrateIfMissing("tenant-1", "live-data",
            List.of("published-doc"), List.of("published-library"));
    }
}
