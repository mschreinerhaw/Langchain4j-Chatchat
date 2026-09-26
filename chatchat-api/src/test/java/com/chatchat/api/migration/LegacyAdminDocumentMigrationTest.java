package com.chatchat.api.migration;

import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.knowledgebase.search.index.RocksDbSearchStore;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.service.SearchService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyAdminDocumentMigrationTest {

    @Test
    void adoptsLegacyAdminDocumentsIntoTheCurrentTenantAndUserModel() {
        RocksDbSearchStore store = mock(RocksDbSearchStore.class);
        SearchService search = mock(SearchService.class);
        SysUserRepository users = mock(SysUserRepository.class);
        SysUser admin = new SysUser();
        admin.setId("admin-uuid");
        admin.setTenantId("tenant-uuid");
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));

        SearchDocument legacy = SearchDocument.builder().docId("legacy-doc").tenantId("tenant-uuid")
            .userId("admin").content("kept content").build();
        SearchDocument current = SearchDocument.builder().docId("current-doc").tenantId("tenant-uuid")
            .userId("another-user-uuid").content("other content").build();
        when(store.listDocumentIds(0)).thenReturn(List.of("legacy-doc", "current-doc"));
        when(store.get("legacy-doc")).thenReturn(Optional.of(legacy));
        when(store.get("current-doc")).thenReturn(Optional.of(current));
        when(search.reassignDocumentOwner("legacy-doc", "tenant-uuid", "admin-uuid"))
            .thenReturn(Optional.of(legacy));

        new LegacyAdminDocumentMigration(store, search, users).migrate();

        verify(search).reassignDocumentOwner("legacy-doc", "tenant-uuid", "admin-uuid");
        verify(search, never()).reassignDocumentOwner("current-doc", "tenant-uuid", "admin-uuid");
    }

    @Test
    void doesNothingUntilTheDatabaseAdminAccountExists() {
        RocksDbSearchStore store = mock(RocksDbSearchStore.class);
        SearchService search = mock(SearchService.class);
        SysUserRepository users = mock(SysUserRepository.class);
        when(users.findByUsername("admin")).thenReturn(Optional.empty());

        new LegacyAdminDocumentMigration(store, search, users).migrate();

        verify(store, never()).listDocumentIds(0);
        verify(search, never()).reassignDocumentOwner(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
