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
    void migrationDoesNotInventAgentScopeWhenLegacyAgentHasNoBindings() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "general-agent"))
            .thenReturn(List.of());
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.migrateIfMissing("tenant-1", "general-agent", List.of(), List.of());

        assertThat(count).isZero();
        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void synchronizationLeavesAgentScopeEmptyWhenOnlyRoleAuthorizationShouldApply() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "general-agent"))
            .thenReturn(List.of());
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.synchronize("tenant-1", "general-agent", List.of(), List.of());

        assertThat(count).isZero();
        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void knowledgeSynchronizationPreservesMcpAgentAndWorkflowBindings() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        SkillResourceScope document = binding("DOCUMENT", "old-doc");
        SkillResourceScope mcp = binding("MCP_TOOL", "document_search");
        SkillResourceScope agent = binding("AGENT", "review-agent");
        SkillResourceScope workflow = binding("WORKFLOW", "evidence-recovery");
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "live-data"))
            .thenReturn(List.of(document, mcp, agent, workflow));
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        service.synchronize("tenant-1", "live-data", List.of("new-doc"), List.of());

        verify(repository).deleteAll(List.of(document));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillResourceScope>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(SkillResourceScope::getResourceId)
            .containsExactly("new-doc");
    }

    @Test
    void fullSynchronizationPersistsEveryGovernedResourceType() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "live-data"))
            .thenReturn(List.of());
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.synchronize("tenant-1", "live-data", List.of("doc-1"), List.of("Docs"),
            List.of("document_search"), List.of("review-agent"), List.of("evidence-recovery"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillResourceScope>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(count).isEqualTo(5);
        assertThat(rows.getValue())
            .extracting(row -> row.getResourceType() + ":" + row.getResourceId())
            .containsExactly("DOCUMENT:doc-1", "KNOWLEDGE_BASE:docs", "MCP_TOOL:document_search",
                "AGENT:review-agent", "WORKFLOW:evidence-recovery");
    }

    @Test
    void migrationRemovesRoleWildcardStoredInAgentScope() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        SkillResourceScope invalid = new SkillResourceScope();
        invalid.setResourceType("DOCUMENT");
        invalid.setResourceId("*");
        when(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant-1", "general-agent"))
            .thenReturn(List.of(invalid));
        SkillResourceScopeSynchronizationService service =
            new SkillResourceScopeSynchronizationService(repository);

        int count = service.migrateIfMissing("tenant-1", "general-agent", List.of(), List.of());

        assertThat(count).isZero();
        verify(repository).deleteAll(List.of(invalid));
        verify(repository).flush();
        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    private SkillResourceScope binding(String type, String id) {
        SkillResourceScope scope = new SkillResourceScope();
        scope.setResourceType(type);
        scope.setResourceId(id);
        scope.setEnabled(true);
        return scope;
    }
}
