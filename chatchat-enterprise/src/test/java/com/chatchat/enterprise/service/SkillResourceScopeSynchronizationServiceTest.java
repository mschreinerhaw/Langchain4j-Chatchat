package com.chatchat.enterprise.service;

import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillResourceScopeSynchronizationServiceTest {

    @Test
    void migratesLegacyDocumentsAndTagsIntoRelationshipRows() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "live-data"))
            .thenReturn(List.of());
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.migrateIfMissing("tenant-1", "live-data",
            List.of("doc-1", "doc-1", "doc-2"), List.of("LiveData", "livedata"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillResourceScope>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(count).isEqualTo(3);
        assertThat(rows.getValue())
            .extracting(row -> row.getResourceType() + ":" + row.getResourceId())
            .containsExactly("DOCUMENT:doc-1", "DOCUMENT:doc-2", "KNOWLEDGE_BASE:livedata");
    }

    @Test
    void existingDatabaseRelationshipRemainsAuthoritativeDuringMigration() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        SkillResourceScope existing = new SkillResourceScope();
        existing.setResourceType("DOCUMENT");
        existing.setResourceId("legacy-doc");
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "live-data"))
            .thenReturn(List.of(existing));
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.migrateIfMissing("tenant-1", "live-data", List.of("legacy-doc"), List.of());

        assertThat(count).isZero();
        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void migrationAddsOnlyBindingsMissingFromAPartialRelationshipModel() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        SkillResourceScope existing = new SkillResourceScope();
        existing.setResourceType("DOCUMENT");
        existing.setResourceId("doc-1");
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "live-data"))
            .thenReturn(List.of(existing));
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.migrateIfMissing("tenant-1", "live-data",
            List.of("doc-1", "doc-2"), List.of("LiveData"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillResourceScope>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(count).isEqualTo(2);
        assertThat(rows.getValue())
            .extracting(row -> row.getResourceType() + ":" + row.getResourceId())
            .containsExactly("DOCUMENT:doc-2", "KNOWLEDGE_BASE:livedata");
    }

    @Test
    void migrationPersistsExplicitAllAuthorizedDocumentsScopeWhenLegacyAgentHasNoBindings() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "general-agent"))
            .thenReturn(List.of());
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.migrateIfMissing("tenant-1", "general-agent", List.of(), List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillResourceScope>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(count).isEqualTo(1);
        assertThat(rows.getValue())
            .extracting(row -> row.getResourceType() + ":" + row.getResourceId())
            .containsExactly("DOCUMENT:*");
    }

    @Test
    void synchronizationPersistsExplicitAllAuthorizedDocumentsScopeForUnrestrictedAgent() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "general-agent"))
            .thenReturn(List.of());
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.synchronize("tenant-1", "general-agent", List.of(), List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillResourceScope>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(count).isEqualTo(1);
        assertThat(rows.getValue().get(0).getResourceId()).isEqualTo("*");
    }
}
